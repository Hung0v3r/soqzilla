// Soqzilla indexer + API + mint relay, one process.
//   Indexer: follows the Soqucoin tip, applies blocks to the ledger, keeps the last FINALITY blocks
//            replayable so a reorg undoes cleanly, saves the finalized ledger to STATE_FILE.
//   API:     read-only JSON for the app and anyone else (CORS open).
//   Relay:   checks a phone's solution, pays the SOQ fee from the relay wallet and broadcasts the mint.
//            The reward goes to the address inside the solution, so the relay cannot redirect it.
import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { sha256 } from "@noble/hashes/sha256";
import * as R from "./lib/rules.mjs";
import { Ledger } from "./lib/ledger.mjs";
import * as chain from "./lib/chain.mjs";
import { wallet, Lanes, opReturnScript, addressToProgram, programToAddress, DUST_THRESHOLD } from "./lib/wallet.mjs";
import { addressToScriptPubKey } from "soq-lightning-sdk/dist/onchain.js";
import { outputScripts } from "./lib/txparse.mjs";
import { marked } from "marked";
import { clientIp, refreshCloudflareRanges } from "./lib/clientip.mjs";

const env = process.env;
const PORT = Number(env.PORT ?? 8080);
const FINALITY = 12;
const STATE_FILE = env.STATE_FILE ?? path.resolve("data", `state-${R.NET}.json`);
const START_HEIGHT = env.START_HEIGHT ? Number(env.START_HEIGHT) : null;
const SPEC = fs.readFileSync(new URL("./SPEC.md", import.meta.url));
const PAGE = fs.readFileSync(new URL("./public/index.html", import.meta.url));
const STYLE = fs.readFileSync(new URL("./public/style.css", import.meta.url));
const TEMPLATE = fs.readFileSync(new URL("./public/page.html", import.meta.url), "utf8");
const API_BODY = fs.readFileSync(new URL("./public/api-body.html", import.meta.url), "utf8");
const SPEC_HTML = marked.parse(SPEC.toString("utf8"));
const hexOf = (w) => [...Buffer.from(w.toUpperCase())].map((b) => b.toString(16).toUpperCase().padStart(2, "0")).join(" ");
/** The site's text pages share one template: header, hex strip, footer. */
const fillPage = (o) => TEMPLATE.replace(/\{\{(\w+)\}\}/g, (_m, k) => o[k] ?? "");
const RULES_HASH = (env.RULES_HASH ?? R.bytesToHex(sha256(SPEC))).toLowerCase();
const RELAY_DAILY_MAX = Number(env.RELAY_DAILY_MAX ?? 3000);
// Safety switch: MINING_PAUSED=1 makes /api/work answer "not ready", and every app (all versions) stops
// hashing within ~20 s and shows the reason. Finds already made are still relayed. Unset to resume.
const MINING_PAUSED = env.MINING_PAUSED && env.MINING_PAUSED !== "0"
  ? (env.MINING_PAUSED_REASON || "Mining is paused for the night. Check back in the morning.") : null;
const GAS_AMOUNT = BigInt(env.GAS_SHORS ?? 5_000_000); // 0.05 SOQ: about three transfers
const GAS_DAILY_MAX = Number(env.GAS_DAILY_MAX ?? 50);  // grants per day across everyone
const FIND_FEE = 1086n * BigInt(env.SOQ_FEE_RATE ?? 1500); // shors: one relayed find is ~1,086 vB
const RELAY_STATE_FILE = path.join(path.dirname(STATE_FILE), `relay-${R.NET}.json`);
const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---------- indexer ----------
let finalized, tip, recent = [], startHeight, lastSave = 0, dirty = false, lastSync = null, syncError = null, chainTip = null;

function load() {
  if (fs.existsSync(STATE_FILE)) {
    const s = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
    finalized = Ledger.restore(s.ledger);
    startHeight = s.startHeight;
    if (finalized.rulesHashHex !== RULES_HASH) {
      // The rules text changed: rebuild from the start height under the new hash (a deploy of the new
      // rules can only be at or after the start height, so nothing is missed).
      log(`state was built for rules ${finalized.rulesHashHex.slice(0, 16)}…, now ${RULES_HASH.slice(0, 16)}…: rebuilding from ${startHeight}`);
      finalized = new Ledger({ rulesHashHex: RULES_HASH }); dirty = true;
    } else log(`loaded state at height ${finalized.height}`);
  } else {
    if (START_HEIGHT === null) throw new Error("no state file: set START_HEIGHT (a SOQ height at or before the deploy)");
    finalized = new Ledger({ rulesHashHex: RULES_HASH });
    startHeight = START_HEIGHT;
    log(`fresh index from height ${startHeight}`);
  }
  tip = finalized.clone();
}

function save(force = false) {
  if (!dirty || (!force && Date.now() - lastSave < 30_000)) return;
  fs.mkdirSync(path.dirname(STATE_FILE), { recursive: true });
  const tmp = `${STATE_FILE}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify({ startHeight, ledger: finalized.snapshot() }));
  fs.renameSync(tmp, STATE_FILE);
  lastSave = Date.now(); dirty = false;
}

async function reorg() {
  for (let i = recent.length - 1; i >= 0; i--) {
    if ((await chain.blockHash(recent[i].height)) === recent[i].hash) {
      log(`reorg: replaced blocks above ${recent[i].height}`);
      recent = recent.slice(0, i + 1);
      tip = finalized.clone();
      for (const b of recent) await tip.applyBlock(b);
      return;
    }
  }
  if (finalized.height === null || (await chain.blockHash(finalized.height)) === finalized.hashes.get(finalized.height)) {
    log("reorg: replaced every recent block"); recent = []; tip = finalized.clone(); return;
  }
  log(`reorg deeper than ${FINALITY} blocks: rebuilding from ${startHeight}`);
  finalized = new Ledger({ rulesHashHex: RULES_HASH }); recent = []; tip = finalized.clone(); dirty = true;
}

async function sync() {
  chainTip = await chain.tipHeight();
  while (true) {
    const next = tip.height === null ? startHeight : tip.height + 1;
    if (next > chainTip) break;
    const bs = await chain.blocks(next, Math.min(chainTip, next + 49));
    for (const b of bs) {
      const prev = recent.length ? recent.at(-1).hash : finalized.hashes.get(finalized.height);
      if (prev && b.prev !== prev) { await reorg(); return; }
      await tip.applyBlock(b);
      recent.push(b);
      while (recent.length > FINALITY) { await finalized.applyBlock(recent.shift()); dirty = true; }
    }
    if (chainTip - tip.height > 100) log(`indexed to ${tip.height} of ${chainTip}`);
    save();
  }
  lastSync = Date.now();
}

async function loop() {
  for (;;) {
    try { await sync(); syncError = null; if (lanes) await lanes.refresh().catch((e) => log("lanes:", e.message)); }
    catch (e) { syncError = e.message; log("sync:", e.message); }
    await new Promise((r) => setTimeout(r, 15_000));
  }
}

// ---------- relay ----------
const lanes = env.RELAY_SEED ? new Lanes(wallet(env.RELAY_SEED)) : null;
const relayPending = new Map(); // pow hash -> { txid, at }
const inflight = new Set();     // "ref:program:nonce" being checked or sent right now
// Gas grants survive restarts: one per program, ever.
const gasGiven = new Map(Object.entries((() => { try { return JSON.parse(fs.readFileSync(RELAY_STATE_FILE, "utf8")).gas ?? {}; } catch { return {}; } })()));
const saveRelayState = () => { try { fs.mkdirSync(path.dirname(RELAY_STATE_FILE), { recursive: true }); fs.writeFileSync(RELAY_STATE_FILE, JSON.stringify({ gas: Object.fromEntries(gasGiven) })); } catch (e) { log("relay state:", e.message); } };
let day = new Date().toISOString().slice(0, 10), relayedToday = 0, gasToday = 0;

function rollDay() { const d = new Date().toISOString().slice(0, 10); if (d !== day) { day = d; relayedToday = 0; gasToday = 0; } }
function prunePending() { for (const [k, v] of relayPending) if (tip.seen.has(k) || Date.now() - v.at > 30 * 60_000) relayPending.delete(k); }

async function relayMint(body) {
  if (!lanes) throw httpError(503, "relay is off");
  rollDay(); prunePending();
  if (relayedToday >= RELAY_DAILY_MAX) throw httpError(503, "relay daily limit reached; try again tomorrow");
  const program = programFrom(body);
  const refHeight = Number(body.refHeight), nonce = BigInt(body.nonce);
  if (!Number.isInteger(refHeight) || nonce < 0n || nonce >= 1n << 64n) throw httpError(400, "bad refHeight or nonce");
  // it will land in the next block or a few after; leave room for the pool to take its time
  if (refHeight < tip.height - (R.REF_WINDOW - 4)) throw httpError(409, "reference block too old; fetch fresh work");
  // Reserve this exact solution before any await, so concurrent copies of it cannot each be paid for.
  const key = `${refHeight}:${program}:${nonce}`;
  if (inflight.has(key)) throw httpError(409, "already being relayed");
  inflight.add(key);
  try {
    limitGlobal("argon2", 120); // every check is a 64 MiB Argon2: cap them for the whole server
    const c = await tip.checkMint({ refHeight, nonce, program: R.hexToBytes(program) });
    if (!c.ok) throw httpError(409, c.reason);
    if (relayPending.has(c.hash)) return { txid: relayPending.get(c.hash).txid, duplicate: true };
    const payload = R.encodeMint({ refHeight, nonce, program: R.hexToBytes(program) });
    const built = await lanes.send([{ value: 0n, scriptPubKey: opReturnScript(payload) }]);
    relayPending.set(c.hash, { txid: built.txid, at: Date.now() });
    relayedToday++;
    log(`relayed mint ${built.txid} for ${programToAddress(program)} (ref ${refHeight})`);
    return { txid: built.txid, reward: String(tip.nextReward()), address: programToAddress(program) };
  } finally {
    inflight.delete(key);
  }
}

async function relayGas(body) {
  if (!lanes) throw httpError(503, "relay is off");
  const program = programFrom(body);
  rollDay();
  // Only for addresses that have mined (each mint is real work), once per address ever, within a daily budget.
  if (!tip.minters.has(program)) throw httpError(403, "fee SOQ is for addresses that have mined at least once");
  if (gasGiven.has(program)) throw httpError(409, "this address has already had its fee SOQ");
  if (gasToday >= GAS_DAILY_MAX) throw httpError(503, "today's fee SOQ is used up; try again tomorrow");
  gasGiven.set(program, Date.now()); gasToday++; // reserve before the await
  try {
    const built = await lanes.send([{ value: GAS_AMOUNT, scriptPubKey: addressToScriptPubKey("sq", programToAddress(program)) }]);
    saveRelayState();
    log(`gas ${built.txid} to ${programToAddress(program)}`);
    return { txid: built.txid, amount: String(GAS_AMOUNT) };
  } catch (e) {
    gasGiven.delete(program); gasToday--;
    throw e;
  }
}

function programFrom(body) {
  if (typeof body.program === "string" && /^[0-9a-f]{64}$/i.test(body.program)) return body.program.toLowerCase();
  if (typeof body.address === "string") { try { return addressToProgram(body.address); } catch {} }
  throw httpError(400, "need an sq1p address or a 64-hex program");
}

// ---------- API ----------
const httpError = (status, message) => Object.assign(new Error(message), { status });
const buckets = new Map();
function limit(ip, name, perMinute) {
  const k = `${ip}:${name}`, now = Date.now();
  const b = buckets.get(k) ?? { n: perMinute, t: now };
  b.n = Math.min(perMinute, b.n + ((now - b.t) / 60_000) * perMinute); b.t = now;
  if (b.n < 1) throw httpError(429, "slow down");
  b.n -= 1; buckets.set(k, b);
  // forget idle callers (never everyone at once, which would reset busy ones too)
  if (buckets.size > 50_000) for (const [key, v] of buckets) if (now - v.t > 120_000) buckets.delete(key);
}
const limitGlobal = (name, perMinute) => limit("*", name, perMinute);

const str = (v) => (typeof v === "bigint" ? String(v) : v);
// Reads are open to any site (CORS *); writes are not: POST answers carry no CORS header and must be JSON,
// which a browser on another site cannot send without a preflight we refuse.
const json = (res, status, obj, cors = true) => { res.writeHead(status, { "content-type": "application/json", ...(cors ? { "access-control-allow-origin": "*" } : {}), "cache-control": "no-store" }); res.end(JSON.stringify(obj, (_k, v) => str(v))); };
const readBody = (req) => new Promise((ok, bad) => { let s = ""; req.on("data", (d) => { s += d; if (s.length > 20_000) { bad(httpError(413, "too big")); req.destroy(); } }); req.on("end", () => { try { ok(s ? JSON.parse(s) : {}); } catch { bad(httpError(400, "bad JSON")); } }); });

// Miners online: distinct callers of /api/work in the last 3 minutes (the app polls every 20 s). Only a
// salted hash is kept, in memory; the salt is random per process, so nothing links back to an IP.
const MINER_SALT = crypto.randomBytes(16);
const minersSeen = new Map();
function sawMiner(ip) {
  const k = crypto.createHash("sha256").update(MINER_SALT).update(String(ip)).digest("hex").slice(0, 20);
  minersSeen.set(k, Date.now());
  if (minersSeen.size > 100_000) minersSeen.clear();
}
function minersOnline() {
  const cutoff = Date.now() - 180_000;
  let n = 0;
  for (const [k, t] of minersSeen) { if (t < cutoff) minersSeen.delete(k); else n++; }
  return n;
}

const html = (res, status, body) => { res.writeHead(status, { "content-type": "text/html; charset=utf-8", "cache-control": "max-age=60" }); res.end(body); };

const NOT_FOUND = fillPage({ title: "Not found", description: "Nothing here.", hex: hexOf("404"), strip: "not found",
  body: `<h1>Not found</h1><p>There's nothing at this address. <a href="/">Back to the home page</a>, or look up an address <a href="/#lookup">there</a>.</p>` });

/** The frozen rules, readable, with what pins them on chain. */
function rulesPage() {
  const g = tip?.genesis;
  const deploy = g
    ? ` The deploy transaction <a href="https://explorer.soqu.org/tx/${g.txid}"><code>${g.txid.slice(0, 16)}…</code></a> in SOQ block ${g.height} wrote that fingerprint to the chain; mining opened at block ${g.launchHeight}.`
    : "";
  const note = `<div class="note"><strong>Frozen at deploy</strong>These rules can never change. Their SHA-256 fingerprint is <code>${RULES_HASH}</code>: download the <a href="/SPEC.md">raw SPEC.md</a> and check it yourself.${deploy}</div>`;
  return fillPage({ title: "Rules", description: "The frozen rules of Soqzilla (SQZ): supply, mining, difficulty, transfers.", hex: hexOf("RULES"), strip: "SPEC.md · frozen at deploy", body: note + SPEC_HTML });
}

function status() {
  const g = tip.genesis;
  const t = tip.height !== null && g && tip.height >= g.launchHeight ? tip.targetAt(tip.height) : R.INITIAL_TARGET;
  return {
    net: R.NET, magic: new TextDecoder().decode(R.MAGIC), rulesHash: RULES_HASH, indexedHeight: tip.height, chainTip, finalizedHeight: finalized.height,
    deployed: !!g, genesis: g, launched: !!g && tip.height >= g.launchHeight,
    minted: tip.minted, cap: R.CAP, mints: tip.mintCount, reward: tip.nextReward(), holders: tip.balances.size, minersOnline: minersOnline(), miningPaused: MINING_PAUSED,
    target: t.toString(16).padStart(64, "0"), expectedHashesPerMint: R.MAX_TARGET / t,
    nextRetargetHeight: tip.epochStart === null ? null : tip.epochStart + R.EPOCH_BLOCKS,
    pow: { algo: "argon2id", memMiB: R.POW.memMiB, iterations: R.POW.iterations, parallelism: R.POW.parallelism, salt: new TextDecoder().decode(R.POW.salt) },
    relay: lanes ? { address: lanes.w.address, balance: lanes.balance(), lanes: lanes.count(), relayedToday, dailyMax: RELAY_DAILY_MAX, findFee: FIND_FEE, gasToday, gasDailyMax: GAS_DAILY_MAX } : null,
    lastSync, syncError,
  };
}

function work() {
  if (MINING_PAUSED) return { ready: false, paused: true, reason: MINING_PAUSED };
  const g = tip.genesis;
  if (!g) return { ready: false, reason: "not deployed yet" };
  if (tip.height === null || tip.height < g.launchHeight) return { ready: false, reason: "not launched yet", launchHeight: g.launchHeight, height: tip.height };
  const refHeight = tip.height, t = tip.targetAt(refHeight);
  return { ready: true, refHeight, refHash: tip.hashes.get(refHeight), target: t.toString(16).padStart(64, "0"), reward: tip.nextReward(), validUntilHeight: refHeight + R.REF_WINDOW - 4, magic: new TextDecoder().decode(R.MAGIC) };
}

function addressView(a) {
  let program;
  try { program = /^[0-9a-f]{64}$/i.test(a) ? a.toLowerCase() : addressToProgram(a); }
  catch { throw httpError(400, "not an sq1p address"); }
  const recentEv = (tip.history.get(program) ?? []).slice().reverse();
  return { address: programToAddress(program), program, balance: tip.balance(program), confirmedBalance: finalized.balance(program), history: recentEv, tipHeight: tip.height };
}

function top(n) {
  return [...tip.balances].sort((a, b) => (b[1] > a[1] ? 1 : b[1] < a[1] ? -1 : 0)).slice(0, n)
    .map(([p, bal], i) => ({ rank: i + 1, address: programToAddress(p), balance: bal, share: Number((bal * 1_000_000n) / (tip.minted || 1n)) / 10_000 }));
}

const server = http.createServer(async (req, res) => {
  const ip = clientIp(req);
  const url = new URL(req.url, "http://x");
  try {
    if (req.method === "OPTIONS") { res.writeHead(204, { "access-control-allow-origin": "*", "access-control-allow-methods": "GET" }); return res.end(); }
    if (!tip) return json(res, 503, { error: "starting" });
    const p = url.pathname;
    if (req.method === "GET") {
      limit(ip, "get", 1200); // generous: mobile carriers put many phones behind one shared IP
      if (p === "/api/health") return json(res, syncError && Date.now() - (lastSync ?? 0) > 600_000 ? 500 : 200, { ok: true, height: tip.height });
      if (p === "/api/status") return json(res, 200, status());
      if (p === "/api/work") { sawMiner(ip); return json(res, 200, work()); }
      if (p === "/api/events") return json(res, 200, { events: tip.events.slice().reverse(), tipHeight: tip.height });
      if (p === "/api/top") return json(res, 200, { top: top(Math.max(1, Math.min(200, Math.floor(Number(url.searchParams.get("limit"))) || 50))) });
      if (p.startsWith("/api/address/")) return json(res, 200, addressView(decodeURIComponent(p.slice(13))));
      if (p.startsWith("/api/utxos/")) {
        limit(ip, "utxos", 30); limitGlobal("utxos", 300);
        let addr;
        try { addr = programToAddress(addressToProgram(decodeURIComponent(p.slice(11)))); } catch { throw httpError(400, "not an sq1p address"); }
        return json(res, 200, { utxos: await chain.utxos(addr) });
      }
      if (p === "/SPEC.md") { res.writeHead(200, { "content-type": "text/markdown; charset=utf-8", "access-control-allow-origin": "*" }); return res.end(SPEC); }
      if (p === "/style.css") { res.writeHead(200, { "content-type": "text/css; charset=utf-8", "cache-control": "max-age=300" }); return res.end(STYLE); }
      if (p === "/rules") return html(res, 200, rulesPage());
      if (p === "/api") return html(res, 200, fillPage({ title: "API", description: "Soqzilla's open JSON API: status, mining work, balances, holders and the relay.", hex: hexOf("API"), strip: "JSON · no key needed", body: API_BODY }));
      if (p === "/" || p === "/index.html") { res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "max-age=60" }); return res.end(PAGE); }
    }
    if (req.method === "POST") {
      if (!String(req.headers["content-type"] ?? "").startsWith("application/json")) throw httpError(415, "send JSON");
      const ok = (obj) => json(res, 200, obj, false);
      if (p === "/api/mint") { limit(ip, "mint", 20); return ok(await relayMint(await readBody(req))); }
      if (p === "/api/gas") { limit(ip, "gas", 2); return ok(await relayGas(await readBody(req))); }
      if (p === "/api/broadcast") {
        limit(ip, "broadcast", 10); limitGlobal("broadcast", 30);
        const { rawTxHex } = await readBody(req);
        if (typeof rawTxHex !== "string" || rawTxHex.length > 20_000) throw httpError(400, "bad rawTxHex");
        // only transactions that carry a Soqzilla message: this is not a general relay for the SOQ network
        let scripts;
        try { scripts = outputScripts(rawTxHex); } catch { throw httpError(400, "bad rawTxHex"); }
        const first = scripts.find((sc) => sc.startsWith("6a"));
        if (!first || !R.fromScriptHex(first)) throw httpError(400, "not a Soqzilla transaction");
        return ok({ txid: await chain.rpc("sendrawtransaction", [rawTxHex]) });
      }
    }
    if (req.method === "GET" && !p.startsWith("/api/") && String(req.headers.accept ?? "").includes("text/html")) return html(res, 404, NOT_FOUND);
    json(res, 404, { error: "not found" });
  } catch (e) {
    json(res, e.status ?? 500, { error: e.message }, req.method !== "POST");
    if (!e.status) log("api:", req.method, url.pathname, e.message);
  }
});

load();
refreshCloudflareRanges().then((n) => log(`cloudflare ranges: ${n}`));
if (lanes) await lanes.refresh().catch((e) => log("lanes:", e.message));
server.listen(PORT, () => log(`soqzilla ${R.NET} on :${PORT}, rules ${RULES_HASH.slice(0, 16)}…, relay ${lanes ? lanes.w.address : "off"}`));
for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => { try { save(true); } finally { process.exit(0); } });
loop();
