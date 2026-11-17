// Minimal raw-transaction reader: the output scripts of a (segwit or legacy) transaction, enough to
// check that a transaction handed to /api/broadcast carries a Soqzilla message.
function reader(hex) {
  if (typeof hex !== "string" || !/^([0-9a-f]{2})+$/i.test(hex)) throw new Error("not hex");
  const b = Buffer.from(hex, "hex");
  let i = 0;
  const need = (n) => { if (i + n > b.length) throw new Error("truncated"); };
  const u8 = () => { need(1); return b[i++]; };
  const skip = (n) => { need(n); i += n; };
  const varint = () => {
    const f = u8();
    if (f < 0xfd) return f;
    const n = f === 0xfd ? 2 : f === 0xfe ? 4 : 8;
    need(n); let v = 0; for (let k = 0; k < n; k++) v += b[i + k] * 2 ** (8 * k); i += n;
    if (v > 1_000_000) throw new Error("count too large");
    return v;
  };
  const bytes = (n) => { need(n); const s = b.subarray(i, i + n); i += n; return s; };
  return { u8, skip, varint, bytes, peek: () => b[i], peek2: () => b[i + 1], get pos() { return i; } };
}

export function outputScripts(hex) {
  const r = reader(hex);
  r.skip(4); // version
  if (r.peek() === 0 && r.peek2() === 1) r.skip(2); // segwit marker + flag
  const nIn = r.varint();
  for (let k = 0; k < nIn; k++) { r.skip(36); r.skip(r.varint()); r.skip(4); }
  const nOut = r.varint();
  const scripts = [];
  for (let k = 0; k < nOut; k++) { r.skip(8); scripts.push(r.bytes(r.varint()).toString("hex")); }
  return scripts;
}
