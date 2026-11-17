// Soqucoin chain access over the public JSON-RPC: batched (a batch of up to 10 calls counts as one
// request against the 100-per-minute limit) and shaped into the blocks the ledger expects.
import { fromScriptHex, programFromScriptHex } from "./rules.mjs";

export const RPC = globalThis.process?.env?.SOQ_RPC_URL ?? "https://mainnet-rpc.soqu.org/";
export const API = (globalThis.process?.env?.SOQ_API_URL ?? "https://mainnet-api.soqu.org/api/v2").replace(/\/$/, "");
const UA = "soqzilla-indexer/1";

let lastCall = 0;
const MIN_GAP_MS = 700; // stays under 100 requests a minute with room for the relay

async function post(body, tries = 0) {
  const wait = lastCall + MIN_GAP_MS - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  lastCall = Date.now();
  const r = await fetch(RPC, { method: "POST", headers: { "content-type": "application/json", "user-agent": UA }, body: JSON.stringify(body), signal: AbortSignal.timeout(30_000) });
  if (r.status === 429) {
    if (tries >= 3) throw new Error("RPC rate limit (429)");
    await new Promise((res) => setTimeout(res, 15_000));
    return post(body, tries + 1);
  }
  const j = await r.json().catch(() => { throw new Error(`RPC HTTP ${r.status}`); });
  return j;
}

export async function rpc(method, params = []) {
  const j = await post({ jsonrpc: "1.0", id: 1, method, params });
  if (j.error) throw new Error(`${method}: ${j.error.message ?? JSON.stringify(j.error)}`);
  return j.result;
}

/** Several calls of one method in one request (at most 10 per batch). */
export async function batch(method, paramsList) {
  const out = [];
  for (let i = 0; i < paramsList.length; i += 10) {
    const slice = paramsList.slice(i, i + 10);
    const res = await post(slice.map((p, k) => ({ jsonrpc: "1.0", id: k, method, params: p })));
    if (!Array.isArray(res)) throw new Error(`${method} batch: ${JSON.stringify(res).slice(0, 200)}`);
    res.sort((a, b) => a.id - b.id);
    for (const r of res) { if (r.error) throw new Error(`${method}: ${r.error.message}`); out.push(r.result); }
  }
  return out;
}

/** Shape a verbose (verbosity 2) block for the ledger. Senders are filled in by blocks(). */
export function shapeBlock(b) {
  return {
    height: b.height,
    hash: b.hash,
    prev: b.previousblockhash ?? null,
    txs: b.tx.map((t) => {
      // SPEC: the first output whose script starts with OP_RETURN (0x6a)
      const op = t.vout.find((o) => typeof o.scriptPubKey?.hex === "string" && o.scriptPubKey.hex.startsWith("6a"));
      const v0 = t.vin[0];
      return { txid: t.txid, vin0: v0?.txid ? { txid: v0.txid, vout: v0.vout } : null, senderProgramHex: null, opReturnHex: op ? op.scriptPubKey.hex : null };
    }),
  };
}

export const tipHeight = () => rpc("getblockcount");

/**
 * Blocks [from, to] inclusive, shaped. For SQZ deploys and transfers the sender is the witness-v1 program
 * of the output the first input spends, read from that output's script (not from the witness, which an
 * attacker could shape freely on a non-v1 spend).
 */
export async function blocks(from, to) {
  const heights = []; for (let h = from; h <= to; h++) heights.push([h]);
  const hashes = await batch("getblockhash", heights);
  const shaped = (await batch("getblock", hashes.map((h) => [h, 2]))).map(shapeBlock);
  const need = [];
  for (const b of shaped) for (const t of b.txs) {
    if (!t.opReturnHex || !t.vin0) continue;
    const p = fromScriptHex(t.opReturnHex);
    if (p && (p.op === "deploy" || p.op === "transfer")) need.push(t);
  }
  if (need.length) {
    const prevs = await batch("getrawtransaction", need.map((t) => [t.vin0.txid, true]));
    need.forEach((t, i) => { t.senderProgramHex = programFromScriptHex(prevs[i]?.vout?.[t.vin0.vout]?.scriptPubKey?.hex); });
  }
  return shaped;
}

export async function blockHash(height) { return rpc("getblockhash", [height]); }

export async function utxos(address) {
  const r = await fetch(`${API}/utxos/${address}`, { headers: { "user-agent": UA }, signal: AbortSignal.timeout(20_000) });
  if (!r.ok) throw new Error(`utxos: HTTP ${r.status}`);
  const j = await r.json();
  return (j.utxos ?? []).filter((u) => u.assetType === 0 || u.assetType == null)
    .map((u) => ({ txid: u.txid, vout: u.vout, value: BigInt(u.value), height: u.height ?? null }));
}
