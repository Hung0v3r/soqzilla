// Test miner: fetches work from an indexer, grinds Argon2 nonces, submits solutions to its relay.
// For dry runs only; the real miner is the Android app.
//   node scripts/test-miner.mjs <serverUrl> <sq1p… address> [count]
import * as R from "../lib/rules.mjs";
import { addressToProgram } from "../lib/wallet.mjs";

const [server, address, countArg] = process.argv.slice(2);
const count = Number(countArg ?? 1);
const program = R.hexToBytes(addressToProgram(address));
const get = async (p) => (await fetch(server + p)).json();
let found = 0, nonce = BigInt(Math.floor(Math.random() * 2 ** 40)) << 16n;

while (found < count) {
  const w = await get("/api/work");
  if (!w.ready) { console.log("waiting:", w.reason); await new Promise((r) => setTimeout(r, 20_000)); continue; }
  const target = BigInt("0x" + w.target);
  const started = Date.now(); let tries = 0;
  while (Date.now() - started < 45_000) {
    nonce++; tries++;
    const h = await R.powHash(w.refHash, program, nonce);
    if (R.hashValue(h) < target) {
      const r = await fetch(server + "/api/mint", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ refHeight: w.refHeight, nonce: String(nonce), address }) }).then((x) => x.json());
      console.log(`found after ${tries} hashes (ref ${w.refHeight}):`, JSON.stringify(r));
      if (r.txid) found++;
      break;
    }
  }
}
