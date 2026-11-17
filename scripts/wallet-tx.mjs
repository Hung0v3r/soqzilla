// Operator transactions from the relay wallet. Dry run unless --broadcast is given.
//   node scripts/wallet-tx.mjs balance
//   node scripts/wallet-tx.mjs fanout <lanes>                 split the wallet into equal coins
//   node scripts/wallet-tx.mjs deploy <launchHeight> [rulesHashHex]
//   node scripts/wallet-tx.mjs transfer <amountSQZ> <sq1p…>   (sender = this wallet)
// Seed: RELAY_SEED (64 hex) or SEED_FILE (a file containing it).
import fs from "node:fs";
import { sha256 } from "@noble/hashes/sha256";
import * as R from "../lib/rules.mjs";
import { wallet, buildTx, broadcast, opReturnScript, addressToProgram } from "../lib/wallet.mjs";
import { utxos, rpc, tipHeight } from "../lib/chain.mjs";

const args = process.argv.slice(2).filter((a) => a !== "--broadcast");
const send = process.argv.includes("--broadcast");
const seed = process.env.RELAY_SEED ?? (process.env.SEED_FILE ? fs.readFileSync(process.env.SEED_FILE, "utf8").match(/[0-9a-f]{64}/i)?.[0] : undefined);
const w = wallet(seed);
const coins = (await utxos(w.address)).sort((a, b) => (b.value > a.value ? 1 : -1));
const soq = (v) => (Number(v) / 1e8).toFixed(8).replace(/0+$/, "").replace(/\.$/, "");
console.log(`net ${R.NET}, wallet ${w.address}, ${coins.length} coins, ${soq(coins.reduce((s, u) => s + u.value, 0n))} SOQ, tip ${await tipHeight()}`);

async function finish(built, what) {
  console.log(`${what}: txid ${built.txid}, ${built.vsize} vB, fee ${soq(built.fee)} SOQ`);
  const dec = await rpc("decoderawtransaction", [built.rawTxHex]);
  console.log(`decoded ok: ${dec.vin.length} in, ${dec.vout.length} out`);
  if (!send) return console.log("dry run: add --broadcast to send");
  const id = await broadcast(built.rawTxHex);
  console.log(`broadcast: ${id}`);
}

const [cmd, a1, a2] = args;
if (cmd === "balance") process.exit(0);
if (cmd === "fanout") {
  const n = Number(a1);
  if (!(n >= 2 && n <= 60)) throw new Error("lanes must be 2..60");
  await finish(buildTx({ w, coins: coins.slice(0, 5), outputs: [], changeOutputs: n }), `fanout into ${n} lanes`);
} else if (cmd === "deploy") {
  const launchHeight = Number(a1);
  const rulesHash = a2 ? R.hexToBytes(a2) : sha256(fs.readFileSync(new URL("../SPEC.md", import.meta.url)));
  console.log(`deploy ${new TextDecoder().decode(R.MAGIC)}: rules ${R.bytesToHex(rulesHash)}, launch height ${launchHeight}`);
  const payload = R.encodeDeploy({ rulesHash, launchHeight });
  await finish(buildTx({ w, coins: [coins.at(-1)], outputs: [{ value: 0n, scriptPubKey: opReturnScript(payload) }] }), "deploy");
} else if (cmd === "transfer") {
  const payload = R.encodeTransfer({ amount: BigInt(a1), to: R.hexToBytes(addressToProgram(a2)) });
  await finish(buildTx({ w, coins: [coins.at(-1)], outputs: [{ value: 0n, scriptPubKey: opReturnScript(payload) }] }), "transfer");
} else {
  console.log("commands: balance | fanout <n> | deploy <launchHeight> [rulesHash] | transfer <amount> <sq1p…>");
}
