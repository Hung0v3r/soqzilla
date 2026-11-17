import { test } from "node:test";
import assert from "node:assert/strict";
import { sha256 } from "@noble/hashes/sha256";
import * as R from "../lib/rules.mjs";
import { Ledger } from "../lib/ledger.mjs";

test("emission ends at exactly 21 billion", () => {
  let minted = 0n, i = 0;
  for (;;) { const r = R.rewardFor(i, minted); if (r === 0n) break; minted += r; i++; }
  assert.equal(minted, R.CAP);
  assert.equal(R.rewardFor(0, 0n), 42_000n);
  assert.equal(R.rewardFor(250_000, 0n), 21_000n);
  console.log(`  mints until cap: ${i.toLocaleString()} (~${(i / 1440 / 365).toFixed(1)} years at one a minute)`);
  // half the supply in the first era
  assert.equal(R.START_REWARD * R.HALVING_MINTS, R.CAP / 2n);
});

test("payloads round-trip and fit one OP_RETURN push", () => {
  const program = sha256(new Uint8Array([1, 2, 3]));
  const m = R.encodeMint({ refHeight: 52_000, nonce: 123456789n, program });
  const d = R.encodeDeploy({ rulesHash: program, launchHeight: 53_000 });
  const t = R.encodeTransfer({ amount: 42_000n, to: program });
  for (const p of [m, d, t]) assert.ok(p.length <= 75);
  const mm = R.fromScriptHex(R.bytesToHex(R.toScript(m)));
  assert.equal(mm.op, "mint"); assert.equal(mm.refHeight, 52_000); assert.equal(mm.nonce, 123456789n);
  assert.deepEqual(mm.program, program);
  assert.equal(R.fromScriptHex(R.bytesToHex(R.toScript(d))).launchHeight, 53_000);
  assert.equal(R.fromScriptHex(R.bytesToHex(R.toScript(t))).amount, 42_000n);
  assert.equal(R.fromScriptHex("6a0548454c4c4f"), null);
});

test("retarget follows the mint rate and is clamped to 4x", () => {
  const T = R.INITIAL_TARGET;
  assert.equal(R.nextTarget(T, 60), T);
  assert.equal(R.nextTarget(T, 120), T / 2n);
  assert.equal(R.nextTarget(T, 6000), T / 4n);
  assert.equal(R.nextTarget(T, 0), T * 4n);
  assert.equal(R.nextTarget(R.MAX_TARGET, 0), R.MAX_TARGET);
});

test("ledger: deploy rules, launch gate, mints, duplicates, per-block cap, transfers", async () => {
  const rulesHash = sha256(new TextEncoder().encode("spec"));
  const L = new Ledger({ rulesHashHex: R.bytesToHex(rulesHash), powCheck: false });
  const progA = R.bytesToHex(sha256(new Uint8Array([7]))), progB = R.bytesToHex(sha256(new Uint8Array([9])));
  const op = (payload) => R.bytesToHex(R.toScript(payload));
  const mk = (height, txs = []) => ({ height, hash: R.bytesToHex(sha256(new Uint8Array([height & 255, height >> 8]))), txs });
  const apply = async (b) => { while (L.height !== null && L.height + 1 < b.height) await L.applyBlock(mk(L.height + 1)); return L.applyBlock(b); };
  const deploy = (launchHeight, sender = R.DEPLOYER, hash = rulesHash) => ({ txid: `d${launchHeight}${sender.slice(0, 4)}`, senderProgramHex: sender, opReturnHex: op(R.encodeDeploy({ rulesHash: hash, launchHeight })) });

  await apply(mk(100, [deploy(170, R.DEPLOYER, sha256(new Uint8Array([0])))]));
  assert.equal(L.genesis, null, "wrong rules hash ignored");
  await apply(mk(101, [deploy(170, progA)]));
  assert.equal(L.genesis, null, "deploy from anyone but the deployer ignored");
  await apply(mk(102, [deploy(4_000_000_000)]));
  assert.equal(L.genesis, null, "launch too far ahead ignored");
  await apply(mk(103, [deploy(110)]));
  assert.equal(L.genesis, null, "launch too soon ignored");
  await apply(mk(104, [deploy(170)]));
  assert.equal(L.genesis?.launchHeight, 170);

  const mint = (refHeight, nonce, program) => ({ txid: `m${refHeight}-${nonce}`, opReturnHex: op(R.encodeMint({ refHeight, nonce, program: R.hexToBytes(program) })) });
  await apply(mk(169, [mint(168, 1n, progA)]));
  assert.equal(L.mintCount, 0, "no mints before launch");
  await apply(mk(170));
  await apply(mk(171, [mint(170, 1n, progA), mint(170, 1n, progA), mint(170, 2n, progA), mint(171, 3n, progA)]));
  assert.equal(L.mintCount, 2, "duplicate and same-block refs rejected");
  assert.equal(L.balance(progA), 84_000n);
  assert.ok(L.minters.has(progA));

  await apply(mk(172, [1n, 2n, 3n, 4n, 5n].map((n) => mint(171, 10n + n, progB))));
  assert.equal(L.mintCount, 5, "only 3 mints credited per block");

  await apply(mk(173, [{ txid: "t1", senderProgramHex: progA, opReturnHex: op(R.encodeTransfer({ amount: 50_000n, to: R.hexToBytes(progB) })) }]));
  assert.equal(L.balance(progB), 3n * 42_000n + 50_000n);
  await apply(mk(174, [{ txid: "t2", senderProgramHex: progA, opReturnHex: op(R.encodeTransfer({ amount: 50_000n, to: R.hexToBytes(progB) })) }]));
  assert.equal(L.balance(progA), 34_000n, "overspend ignored");
  await apply(mk(175, [{ txid: "t3", senderProgramHex: null, opReturnHex: op(R.encodeTransfer({ amount: 1n, to: R.hexToBytes(progB) })) }]));
  assert.equal(L.events.filter((e) => e.type === "transfer").length, 1, "transfer without a v1 sender ignored");

  await apply(mk(200, [mint(175, 9n, progA)]));
  assert.equal(L.mintCount, 5, "ref older than 20 blocks rejected");

  const copy = Ledger.restore(L.snapshot(), { powCheck: false });
  assert.equal(copy.balance(progB), L.balance(progB), "snapshot keeps balances");
  assert.equal(copy.height, 200);
  assert.ok(copy.minters.has(progB));
  await copy.applyBlock(mk(201, [mint(199, 4n, progA)]));
  assert.equal(copy.mintCount, 6);
  assert.equal(L.mintCount, 5, "clone is independent");
});

test("retarget happens every 60 blocks after launch", async () => {
  const rulesHash = sha256(new TextEncoder().encode("spec"));
  const L = new Ledger({ rulesHashHex: R.bytesToHex(rulesHash), powCheck: false });
  const mk = (height, txs = []) => ({ height, hash: R.bytesToHex(sha256(new Uint8Array([height & 255, height >> 8, 1]))), txs });
  await L.applyBlock(mk(1, [{ txid: "dep", senderProgramHex: R.DEPLOYER, opReturnHex: R.bytesToHex(R.toScript(R.encodeDeploy({ rulesHash, launchHeight: 61 }))) }]));
  for (let h = 2; h <= 61 + 60; h++) await L.applyBlock(mk(h));
  assert.equal(L.targets.length, 2);
  assert.equal(L.targets[1].fromHeight, 121);
  assert.equal(L.target, R.INITIAL_TARGET * 4n, "an hour with no mints makes it 4x easier");
});

test("sender comes only from a witness-v1 output script", () => {
  assert.equal(R.programFromScriptHex("5120" + "ab".repeat(32)), "ab".repeat(32));
  assert.equal(R.programFromScriptHex("5620" + "ab".repeat(32)), null, "other witness versions are not senders");
  assert.equal(R.programFromScriptHex("0014" + "ab".repeat(20)), null);
  assert.equal(R.programFromScriptHex(undefined), null);
});

test("real Argon2 check accepts a found solution and rejects a miss", async () => {
  const program = sha256(new Uint8Array([5]));
  const ref = "ab".repeat(32);
  const easy = R.MAX_TARGET / 4n;
  let nonce = 0n, h;
  for (;; nonce++) { h = await R.powHash(ref, program, nonce); if (R.hashValue(h) < easy) break; }
  assert.ok(R.hashValue(h) < easy);
  assert.equal(R.bytesToHex(await R.powHash(ref, program, nonce)), R.bytesToHex(h), "deterministic");
});
