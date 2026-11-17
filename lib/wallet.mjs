// The relay's hot wallet: builds and signs SOQ transactions with ML-DSA-44 (soq-lightning-sdk) and
// spreads spending over many coins ("lanes") so a burst of mints does not build one long chain of
// unconfirmed transactions.
import { mlDsaKeygen, deriveAddress, addressToScriptPubKey, encodeAddress, decodeAddress, bip143Sighash, assembleWitness, serializeTxHex, txid as txidOf, nobleMlDsa, SIGHASH_ALL, DEFAULT_SEQUENCE, TX_VERSION, DUST_THRESHOLD } from "soq-lightning-sdk/dist/onchain.js";
import { rpc, utxos } from "./chain.mjs";
import { hexToBytes, bytesToHex } from "./rules.mjs";

export const HRP = "sq";
export const FEE_RATE = BigInt(globalThis.process?.env?.SOQ_FEE_RATE ?? 1500); // shors per vbyte; network median ~1,000
export const MAX_DEPTH = 12; // unconfirmed ancestors allowed per lane (mempool limit is 25)
export { DUST_THRESHOLD };

export function wallet(seedHex) {
  if (!seedHex || !/^[0-9a-f]{64}$/i.test(seedHex)) throw new Error("wallet seed must be 64 hex chars");
  const { publicKey, secretKey } = mlDsaKeygen(hexToBytes(seedHex));
  if (publicKey[0] === 0xff) throw new Error("seed gives an unspendable key; generate another");
  return { publicKey, secretKey, address: deriveAddress(publicKey, HRP) };
}

export const programToAddress = (programHex) => encodeAddress(HRP, hexToBytes(programHex));
export function addressToProgram(addr) {
  const { witver, program } = decodeAddress(HRP, addr);
  if (witver !== 1 || program.length !== 32) throw new Error("not an sq1p address");
  return bytesToHex(program);
}
export const opReturnScript = (payload) => {
  if (payload.length > 75) throw new Error("payload too long");
  return new Uint8Array([0x6a, payload.length, ...payload]);
};

function vsizeOf(tx) {
  const full = serializeTxHex(tx).length / 2;
  const base = full - tx.vin.reduce((s, i) => s + 1 + i.witness.reduce((a, w) => a + (w.length < 0xfd ? 1 : 3) + w.length, 0), 0) - 2;
  return Math.ceil((base * 4 + (full - base)) / 4);
}

/** Sign a tx spending `coins`, paying `outputs` ({value, scriptPubKey}) with the rest back to the wallet. */
export function buildTx({ w, coins, outputs, feeRate = FEE_RATE, changeOutputs = 1 }) {
  if (!coins.length) throw new Error("no coins");
  const changeSpk = addressToScriptPubKey(HRP, w.address);
  const total = coins.reduce((s, u) => s + u.value, 0n);
  const paid = outputs.reduce((s, o) => s + o.value, 0n);
  const make = (fee) => {
    const change = total - paid - fee;
    if (change <= DUST_THRESHOLD * BigInt(changeOutputs)) throw new Error(`insufficient funds: ${total} in, ${paid} out, fee ${fee}`);
    const each = change / BigInt(changeOutputs), extra = change - each * BigInt(changeOutputs);
    const vout = [...outputs];
    for (let i = 0; i < changeOutputs; i++) vout.push({ value: each + (i === 0 ? extra : 0n), scriptPubKey: changeSpk });
    const tx = { version: TX_VERSION, locktime: 0, vin: coins.map((u) => ({ txid: hexToBytes(u.txid).reverse(), vout: u.vout, sequence: DEFAULT_SEQUENCE, scriptPubKey: changeSpk })), vout };
    tx.vin.forEach((input, i) => { input.witness = assembleWitness(nobleMlDsa.sign(bip143Sighash(tx, i, coins[i].value, SIGHASH_ALL), w.secretKey), w.publicKey, SIGHASH_ALL); });
    return tx;
  };
  let tx = make(4000n * FEE_RATE);
  const fee = BigInt(vsizeOf(tx)) * feeRate;
  tx = make(fee);
  const id = txidOf(tx);
  const firstChange = outputs.length;
  return {
    rawTxHex: serializeTxHex(tx), txid: id, fee, vsize: vsizeOf(tx),
    change: tx.vout.slice(firstChange).map((o, i) => ({ txid: id, vout: firstChange + i, value: o.value })),
  };
}

export const broadcast = (rawTxHex) => rpc("sendrawtransaction", [rawTxHex]);

/**
 * Lanes: every coin the wallet owns is a lane. Spending one replaces it with its change, one level deeper
 * in unconfirmed ancestors. Confirmed coins from the address API reset depth to 0.
 */
export class Lanes {
  constructor(w) { this.w = w; this.coins = new Map(); this.spent = new Set(); this.busy = Promise.resolve(); }
  key(u) { return `${u.txid}:${u.vout}`; }

  async refresh() {
    const listed = await utxos(this.w.address);
    const ids = new Set(listed.map((u) => this.key(u)));
    for (const k of this.spent) if (!ids.has(k)) this.spent.delete(k); // gone from the API: our spend is in
    // The API is the truth. The only coins kept that it does not list are this relay's own change from the last
    // 30 minutes, which the API may not have caught up with. Anything else it stopped listing was spent
    // elsewhere (an operator script, another process) and must never be picked again.
    for (const [k, c] of this.coins) if (ids.has(k) || !(c.added && Date.now() - c.added < 30 * 60_000)) this.coins.delete(k);
    for (const u of listed) if (!this.spent.has(this.key(u))) this.coins.set(this.key(u), { ...u, depth: u.height ? 0 : 1 });
  }

  balance() { let s = 0n; for (const c of this.coins.values()) s += c.value; return s; }
  count() { return this.coins.size; }

  pick(minValue) {
    let best = null;
    for (const c of this.coins.values()) if (c.value >= minValue && c.depth < MAX_DEPTH && (!best || c.depth < best.depth || (c.depth === best.depth && c.value > best.value))) best = c;
    return best;
  }

  /** Spend one lane: build, broadcast, record the change. Serialised so two requests never take one coin. */
  send(outputs, { dryRun = false } = {}) {
    const run = async () => {
      const need = outputs.reduce((s, o) => s + o.value, 0n) + 2000n * FEE_RATE + DUST_THRESHOLD;
      const coin = this.pick(need);
      if (!coin) throw Object.assign(new Error("relay busy or out of SOQ; try again in a minute"), { status: 503 });
      const built = buildTx({ w: this.w, coins: [coin], outputs });
      if (dryRun) return { ...built, dryRun: true };
      const sent = await broadcast(built.rawTxHex);
      if (sent !== built.txid) throw new Error(`node returned ${sent}, expected ${built.txid}`);
      this.coins.delete(this.key(coin)); this.spent.add(this.key(coin));
      for (const ch of built.change) this.coins.set(this.key(ch), { ...ch, height: null, depth: coin.depth + 1, added: Date.now() });
      return built;
    };
    const p = this.busy.then(run, run);
    this.busy = p.catch(() => {});
    return p;
  }
}
