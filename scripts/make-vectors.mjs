// Known answers for the Android app's unit tests: the Kotlin code must reproduce these byte for byte.
import fs from "node:fs";
import { sha256 } from "@noble/hashes/sha256";
import { ml_dsa44 } from "@noble/post-quantum/ml-dsa.js";
import { argon2id } from "hash-wasm";
import * as R from "../lib/rules.mjs";
import { mlDsaKeygen, deriveAddress, addressToScriptPubKey, bip143Sighash, assembleWitness, serializeTxHex, txid as txidOf, SIGHASH_ALL, DEFAULT_SEQUENCE, TX_VERSION } from "soq-lightning-sdk/dist/onchain.js";

const hex = R.bytesToHex, unhex = R.hexToBytes;
const seed = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
const { publicKey, secretKey } = mlDsaKeygen(seed);
const program = sha256(publicKey);
const address = deriveAddress(publicKey, "sq");
const toProgram = sha256(new TextEncoder().encode("recipient"));

const refHash = "ab".repeat(32), nonce = 123456789n;
const powIn = R.powInput(refHash, program, nonce);
const pow = await R.powHash(refHash, program, nonce);
const plain = await argon2id({ password: new TextEncoder().encode("password"), salt: new TextEncoder().encode("somesalt"), parallelism: 1, iterations: 1, memorySize: 64 * 1024, hashLength: 32, outputType: "hex" });

// deterministic signature check
const msg = sha256(new TextEncoder().encode("soqzilla"));
const s1 = ml_dsa44.sign(msg, secretKey, { extraEntropy: false }), s2 = ml_dsa44.sign(msg, secretKey, { extraEntropy: false });
if (hex(s1) !== hex(s2)) throw new Error("noble signing is not deterministic with extraEntropy:false");
if (!ml_dsa44.verify(s1, msg, publicKey)) throw new Error("verify failed");

// a transfer tx, signed deterministically
const coin = { txid: "11".repeat(32), vout: 1, value: 2_500_000_000n };
const payload = R.encodeTransfer({ amount: 10_000n, to: toProgram });
const spk = addressToScriptPubKey("sq", address);
const feeRate = 1500n;
const mk = (fee) => {
  const tx = { version: TX_VERSION, locktime: 0, vin: [{ txid: unhex(coin.txid).reverse(), vout: coin.vout, sequence: DEFAULT_SEQUENCE, scriptPubKey: spk }],
    vout: [{ value: 0n, scriptPubKey: Uint8Array.from([0x6a, payload.length, ...payload]) }, { value: coin.value - fee, scriptPubKey: spk }] };
  const digest = bip143Sighash(tx, 0, coin.value, SIGHASH_ALL);
  tx.vin[0].witness = assembleWitness(ml_dsa44.sign(digest, secretKey, { extraEntropy: false }), publicKey, SIGHASH_ALL);
  return { tx, digest };
};
const vsizeOf = (tx) => { const full = serializeTxHex(tx).length / 2; const wit = tx.vin.reduce((s, i) => s + 1 + i.witness.reduce((a, w) => a + (w.length < 0xfd ? 1 : 3) + w.length, 0), 0) + 2; const base = full - wit; return Math.ceil((base * 4 + (full - base)) / 4); };
let { tx } = mk(0n); const vsize = vsizeOf(tx); const fee = BigInt(vsize) * feeRate;
const fin = mk(fee);

const v = {
  seed: hex(seed), publicKey: hex(publicKey), program: hex(program), address, recipientProgram: hex(toProgram),
  argon2Plain: { password: "password", salt: "somesalt", memKiB: 65536, t: 1, p: 1, hash: plain },
  pow: { refHash, nonce: String(nonce), input: hex(powIn), hash: hex(pow), salt: new TextDecoder().decode(R.POW.salt) },
  sign: { message: hex(msg), signature: hex(s1) },
  transfer: { coinTxid: coin.txid, coinVout: coin.vout, coinValue: String(coin.value), payload: hex(payload), feeRate: String(feeRate), vsize, fee: String(fee), sighash: hex(fin.digest), rawTx: serializeTxHex(fin.tx), spendTxid: txidOf(fin.tx) },
};
fs.writeFileSync(process.argv[2], JSON.stringify(v, null, 1));
console.log("vectors:", address, "vsize", vsize, "fee", String(fee), "txid", v.transfer.spendTxid, "pow", v.pow.hash.slice(0, 16));
