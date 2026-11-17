// Soqzilla (SQZ) consensus rules: payload formats, proof of work, emission and difficulty.
// The indexer (lib/ledger.mjs) and every miner must agree on this file byte for byte.
// DRAFT until the deploy transaction commits sha256(SPEC.md) on Soqucoin.
import { argon2id } from "hash-wasm";

// SQZ_NET=test swaps the tag to "SQX" and makes mining trivially easy, for dry runs on mainnet that the
// real indexer ignores. Nothing else differs.
export const NET = globalThis.process?.env?.SQZ_NET === "test" ? "test" : "main";
export const MAGIC = NET === "test" ? Uint8Array.from([0x53, 0x51, 0x58]) : Uint8Array.from([0x53, 0x51, 0x5a]); // "SQX" / "SQZ"
export const VERSION = 1;
export const OP = { DEPLOY: 0x44, MINT: 0x4d, TRANSFER: 0x54 }; // 'D' 'M' 'T'

export const CAP = 21_000_000_000n;
export const START_REWARD = 42_000n;
export const HALVING_MINTS = 250_000n;
export const MIN_REWARD = 1n;

export const POW = { memMiB: 64, iterations: 1, parallelism: 1, salt: new TextEncoder().encode("soqzilla/v1/pow!") };
export const REF_WINDOW = 20;    // a mint must point at one of the 20 SOQ blocks before the block that includes it
export const EPOCH_BLOCKS = 60;  // difficulty retargets every 60 SOQ blocks (about an hour)
export const MAX_STEP = 4n;      // at most 4x easier or harder per retarget
export const MAX_TARGET = (1n << 256n) - 1n;
// expected hashes per mint at launch: about 25 phones on the real token, about one phone (~15 H/s) on test
export const INITIAL_WORK = NET === "test" ? 900n : 60_000n;
export const INITIAL_TARGET = MAX_TARGET / INITIAL_WORK;

/** Only this program may deploy (sq1pa7hzz9r7j400gt3vptulaz8g09jn0ypxxxfjww83mgtku92raljqeyzefq). */
export const DEPLOYER = "efae21147e955ef42e2c0af9fe88e8796537902631932738f1da176e1543efe4";
/** The launch height must be this many blocks after the deploy's block (60 = an hour, 10,080 = a week). */
export const LAUNCH_DELAY_MIN = NET === "test" ? 2 : 60;
export const LAUNCH_DELAY_MAX = 10_080;
/** At most this many mints are credited per SOQ block; later ones in the block are ignored. */
export const MAX_MINTS_PER_BLOCK = 3;

/** The sender of a transaction: the witness-v1 program its first input spends, from that output's script. */
export function programFromScriptHex(hex) {
  const m = /^5120([0-9a-f]{64})$/.exec(hex ?? "");
  return m ? m[1] : null;
}

const be = (n, bytes) => { const o = new Uint8Array(bytes); let v = BigInt(n); for (let i = bytes - 1; i >= 0; i--) { o[i] = Number(v & 0xffn); v >>= 8n; } return o; };
const readBE = (b, off, len) => { let v = 0n; for (let i = 0; i < len; i++) v = (v << 8n) | BigInt(b[off + i]); return v; };
export const hexToBytes = (h) => Uint8Array.from(h.match(/../g) ?? [], (x) => parseInt(x, 16));
export const bytesToHex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
const cat = (...parts) => { const o = new Uint8Array(parts.reduce((s, p) => s + p.length, 0)); let i = 0; for (const p of parts) { o.set(p, i); i += p.length; } return o; };
const head = (op) => cat(MAGIC, [VERSION, op]);

// ---- payloads (the bytes pushed after OP_RETURN) ----
export const encodeDeploy = ({ rulesHash, launchHeight }) => cat(head(OP.DEPLOY), rulesHash, be(launchHeight, 4));
export const encodeMint = ({ refHeight, nonce, program }) => cat(head(OP.MINT), be(refHeight, 4), be(nonce, 8), program);
export const encodeTransfer = ({ amount, to }) => cat(head(OP.TRANSFER), be(amount, 8), to);

export function decodePayload(p) {
  if (p.length < 5 || p[0] !== MAGIC[0] || p[1] !== MAGIC[1] || p[2] !== MAGIC[2] || p[3] !== VERSION) return null;
  const op = p[4];
  if (op === OP.DEPLOY && p.length === 41) return { op: "deploy", rulesHash: p.slice(5, 37), launchHeight: Number(readBE(p, 37, 4)) };
  if (op === OP.MINT && p.length === 49) return { op: "mint", refHeight: Number(readBE(p, 5, 4)), nonce: readBE(p, 9, 8), program: p.slice(17, 49) };
  if (op === OP.TRANSFER && p.length === 45) return { op: "transfer", amount: readBE(p, 5, 8), to: p.slice(13, 45) };
  return null;
}

/** OP_RETURN script <-> payload (single direct push). */
export const toScript = (payload) => cat([0x6a, payload.length], payload);
export function fromScriptHex(hex) {
  const b = hexToBytes(hex);
  if (b[0] !== 0x6a || b.length < 2 || b[1] > 75 || b.length !== 2 + b[1]) return null;
  return decodePayload(b.slice(2));
}

// ---- proof of work ----
/** Argon2id over "SQZ"‖v‖refBlockHash‖program‖nonce. refBlockHash is the hex from getblockhash, decoded as written. */
export function powInput(refBlockHashHex, program, nonce) {
  return cat(head(OP.MINT), hexToBytes(refBlockHashHex), program, be(nonce, 8));
}
export async function powHash(refBlockHashHex, program, nonce) {
  return argon2id({ password: powInput(refBlockHashHex, program, nonce), salt: POW.salt, parallelism: POW.parallelism, iterations: POW.iterations, memorySize: POW.memMiB * 1024, hashLength: 32, outputType: "binary" });
}
export const hashValue = (h) => readBE(h, 0, 32);

// ---- emission ----
/** Reward for the mint with this 0-based index, given how much has been minted before it. */
export function rewardFor(index, minted) {
  const remaining = CAP - minted;
  if (remaining <= 0n) return 0n;
  const era = BigInt(index) / HALVING_MINTS;
  let r = era >= 64n ? 0n : START_REWARD >> era;
  if (r < MIN_REWARD) r = MIN_REWARD;
  return r > remaining ? remaining : r;
}

// ---- difficulty ----
/** Target for the next epoch from this epoch's target and how many mints its blocks included. */
export function nextTarget(target, mintsInEpoch) {
  const lo = target / MAX_STEP, hi = target * MAX_STEP;
  let t = mintsInEpoch === 0 ? hi : (target * BigInt(EPOCH_BLOCKS)) / BigInt(mintsInEpoch);
  if (t < lo) t = lo;
  if (t > hi) t = hi;
  if (t > MAX_TARGET) t = MAX_TARGET;
  if (t < 1n) t = 1n;
  return t;
}
