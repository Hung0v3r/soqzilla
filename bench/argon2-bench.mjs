// How many Argon2id hashes per second this device manages at a few memory sizes.
// Usage: node bench/argon2-bench.mjs [seconds-per-setting]
import { argon2id } from "hash-wasm";
import { randomBytes } from "node:crypto";

const secs = Number(process.argv[2] ?? 8);
const salt = new TextEncoder().encode("soqzilla-bench-1");
const settings = [
  { memMiB: 8, t: 1 },
  { memMiB: 16, t: 1 },
  { memMiB: 32, t: 1 },
  { memMiB: 64, t: 1 },
];

for (const s of settings) {
  const pw = randomBytes(80);
  let n = 0;
  const end = Date.now() + secs * 1000;
  while (Date.now() < end) {
    pw.writeUInt32LE(n, 76);
    await argon2id({ password: pw, salt, parallelism: 1, iterations: s.t, memorySize: s.memMiB * 1024, hashLength: 32, outputType: "binary" });
    n++;
  }
  console.log(`argon2id m=${s.memMiB}MiB t=${s.t} p=1: ${(n / secs).toFixed(1)} H/s (one thread)`);
}
