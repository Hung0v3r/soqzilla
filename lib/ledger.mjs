// Soqzilla ledger: applies Soqucoin blocks in order and keeps balances, supply and difficulty.
// Pure and deterministic: feed it the same blocks and it ends in the same state. The indexer wraps it
// with chain fetching, storage and reorg handling.
import { sha256 } from "@noble/hashes/sha256";
import * as R from "./rules.mjs";

/**
 * A block, as the ledger wants it:
 *   { height, hash, txs: [{ txid, senderProgramHex, opReturnHex }] }
 * senderProgramHex = the witness-v1 program of the output the tx's first input spends (from that output's
 *   script 5120‖program), or null if it is not one. Only deploys and transfers need it.
 * opReturnHex = the scriptPubKey hex of the tx's first output whose script starts with OP_RETURN, or null.
 */

// Argon2 results, shared by every ledger in the process, so replaying blocks after a reorg is cheap.
const powCache = new Map();
async function cachedPow(refHash, program, nonce) {
  const k = `${refHash}:${R.bytesToHex(program)}:${nonce}`;
  let h = powCache.get(k);
  if (!h) {
    h = await R.powHash(refHash, program, nonce);
    powCache.set(k, h);
    if (powCache.size > 20_000) powCache.delete(powCache.keys().next().value);
  }
  return h;
}

const KEEP_HASHES = R.REF_WINDOW + 5;
const KEEP_EVENTS = 300, KEEP_PER_ADDRESS = 25, KEEP_TARGETS = 200;

export class Ledger {
  constructor({ rulesHashHex, powCheck = true } = {}) {
    this.rulesHashHex = rulesHashHex;   // sha256 of SPEC.md; only a deploy carrying it counts
    this.powCheck = powCheck;           // tests may switch the Argon2 check off
    this.height = null;                 // last applied block
    this.genesis = null;                // { txid, height, launchHeight }
    this.balances = new Map();          // program hex -> bigint
    this.minted = 0n;
    this.mintCount = 0;
    this.seen = new Map();              // pow hash hex -> ref height (only the last REF_WINDOW blocks matter)
    this.target = R.INITIAL_TARGET;
    this.epochStart = null;
    this.epochMints = 0;
    this.targets = [];                  // [{ fromHeight, target }]
    this.events = [];                   // recent credited mints and transfers, newest last
    this.history = new Map();           // program hex -> recent events touching it
    this.hashes = new Map();            // height -> block hash, recent only
    this.minters = new Set();           // programs that have been credited at least one mint
    this.blockMints = 0;                // mints credited in the block being applied
  }

  targetAt(height) {
    for (let i = this.targets.length - 1; i >= 0; i--) if (height >= this.targets[i].fromHeight) return this.targets[i].target;
    return null;
  }
  balance(programHex) { return this.balances.get(programHex) ?? 0n; }
  nextReward() { return R.rewardFor(this.mintCount, this.minted); }

  async applyBlock(block) {
    if (this.height !== null && block.height !== this.height + 1) throw new Error(`ledger at ${this.height}, got block ${block.height}`);
    this.height = block.height;
    this.blockMints = 0;
    this.hashes.set(block.height, block.hash);
    this.hashes.delete(block.height - KEEP_HASHES);
    for (const [k, ref] of this.seen) if (ref < block.height - R.REF_WINDOW - 1) this.seen.delete(k);
    if (this.genesis && block.height >= this.genesis.launchHeight) this.#roll(block.height);
    for (const tx of block.txs) await this.#applyTx(block, tx);
  }

  // Retarget at epoch boundaries; runs before the block's own txs.
  #roll(height) {
    if (this.epochStart === null) { this.epochStart = height; this.targets.push({ fromHeight: height, target: this.target }); return; }
    if (height - this.epochStart >= R.EPOCH_BLOCKS) {
      this.target = R.nextTarget(this.target, this.epochMints);
      this.epochStart = height; this.epochMints = 0;
      this.targets.push({ fromHeight: height, target: this.target });
      if (this.targets.length > KEEP_TARGETS) this.targets.shift();
    }
  }

  #record(ev, ...programs) {
    this.events.push(ev);
    if (this.events.length > KEEP_EVENTS) this.events.shift();
    for (const p of new Set(programs)) {
      const h = this.history.get(p) ?? [];
      h.push(ev);
      if (h.length > KEEP_PER_ADDRESS) h.shift();
      this.history.set(p, h);
    }
  }
  #credit(p, amt) { this.balances.set(p, this.balance(p) + amt); }

  /** Would this mint be accepted if included in the next block? Returns { ok, reason, hash }. */
  async checkMint({ refHeight, nonce, program }, inclusionHeight = this.height + 1) {
    if (!this.genesis) return { ok: false, reason: "not deployed" };
    if (inclusionHeight < this.genesis.launchHeight) return { ok: false, reason: "before launch" };
    if (refHeight < this.genesis.launchHeight) return { ok: false, reason: "reference before launch" };
    if (refHeight >= inclusionHeight || refHeight < inclusionHeight - R.REF_WINDOW) return { ok: false, reason: "reference block out of window" };
    const refHash = this.hashes.get(refHeight);
    const target = this.targetAt(refHeight);
    if (!refHash || target === null) return { ok: false, reason: "unknown reference block" };
    const h = this.powCheck ? await cachedPow(refHash, program, nonce) : sha256(R.powInput(refHash, program, nonce));
    if (this.powCheck && R.hashValue(h) >= target) return { ok: false, reason: "work above target" };
    const key = R.bytesToHex(h);
    if (this.seen.has(key)) return { ok: false, reason: "already credited" };
    if (this.nextReward() === 0n) return { ok: false, reason: "supply exhausted" };
    return { ok: true, hash: key };
  }

  async #applyTx(block, tx) {
    if (!tx.opReturnHex) return;
    const p = R.fromScriptHex(tx.opReturnHex);
    if (!p) return;

    if (p.op === "deploy") {
      if (this.genesis || R.bytesToHex(p.rulesHash) !== this.rulesHashHex) return;
      if (tx.senderProgramHex !== R.DEPLOYER) return; // only the named deployer can launch
      const delay = p.launchHeight - block.height;
      if (delay < R.LAUNCH_DELAY_MIN || delay > R.LAUNCH_DELAY_MAX) return;
      this.genesis = { txid: tx.txid, height: block.height, launchHeight: p.launchHeight };
      return;
    }
    if (!this.genesis || block.height < this.genesis.launchHeight) return;

    if (p.op === "mint") {
      if (this.blockMints >= R.MAX_MINTS_PER_BLOCK) return;
      const c = await this.checkMint(p, block.height);
      if (!c.ok) return;
      const reward = this.nextReward();
      this.seen.set(c.hash, p.refHeight);
      const to = R.bytesToHex(p.program);
      this.#credit(to, reward);
      this.minted += reward; this.mintCount++; this.epochMints++; this.blockMints++;
      this.minters.add(to);
      this.#record({ type: "mint", txid: tx.txid, height: block.height, to, amount: reward, index: this.mintCount - 1 }, to);
      return;
    }

    if (p.op === "transfer") {
      const from = tx.senderProgramHex;
      if (!from || p.amount <= 0n) return;
      if (this.balance(from) < p.amount) return;
      const to = R.bytesToHex(p.to);
      this.balances.set(from, this.balance(from) - p.amount);
      this.#credit(to, p.amount);
      this.#record({ type: "transfer", txid: tx.txid, height: block.height, from, to, amount: p.amount }, from, to);
    }
  }

  // ---- persistence: plain JSON, bigints as strings ----
  snapshot() {
    const big = (v) => (typeof v === "bigint" ? `${v}n` : v);
    return JSON.stringify({
      v: 1, net: R.NET, rulesHashHex: this.rulesHashHex, height: this.height, genesis: this.genesis,
      balances: [...this.balances], minted: this.minted, mintCount: this.mintCount, seen: [...this.seen],
      target: this.target, epochStart: this.epochStart, epochMints: this.epochMints, targets: this.targets,
      events: this.events, history: [...this.history], hashes: [...this.hashes], minters: [...this.minters],
    }, (_k, v) => big(v));
  }
  static restore(json, opts = {}) {
    const s = JSON.parse(json, (_k, v) => (typeof v === "string" && /^\d+n$/.test(v) ? BigInt(v.slice(0, -1)) : v));
    if (s.v !== 1 || s.net !== R.NET) throw new Error(`snapshot is for net ${s.net}, running ${R.NET}`);
    const L = new Ledger({ rulesHashHex: s.rulesHashHex, ...opts });
    Object.assign(L, {
      height: s.height, genesis: s.genesis, balances: new Map(s.balances), minted: s.minted, mintCount: s.mintCount,
      seen: new Map(s.seen), target: s.target, epochStart: s.epochStart, epochMints: s.epochMints, targets: s.targets,
      events: s.events, history: new Map(s.history), hashes: new Map(s.hashes), minters: new Set(s.minters ?? []),
    });
    return L;
  }
  clone() { return Ledger.restore(this.snapshot(), { powCheck: this.powCheck }); }
}
