package com.soqzilla.app.core

data class Coin(val txid: String, val vout: Int, val value: Long)
class TxOut(val value: Long, val script: ByteArray)
class BuiltTx(val rawHex: String, val txid: String, val fee: Long, val vsize: Int)

/** Soqucoin transactions: segwit layout, BIP-143 sighash over witness-v1 ML-DSA-44 inputs (port of soq-lightning-sdk). */
object Tx {
    private const val VERSION = 2L
    private const val SEQUENCE = 0xffffffffL
    const val DUST = 100_000L
    private const val SIGHASH_ALL = 1
    private const val SIG_LEN = 2420
    private const val PUB_LEN = 1312

    fun scriptFor(program: ByteArray) = byteArrayOf(0x51, 0x20) + program

    private fun outpoint(c: Coin) = c.txid.unhex().reversedArray() + le(c.vout.toLong(), 4)
    private fun ser(o: TxOut) = le(o.value, 8) + compactSize(o.script.size) + o.script
    private fun cat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var i = 0
        for (p in parts) { p.copyInto(out, i); i += p.size }
        return out
    }

    fun sighash(coins: List<Coin>, outs: List<TxOut>, i: Int, scriptCode: ByteArray): ByteArray {
        val prevouts = sha256d(cat(coins.map(::outpoint)))
        val sequences = sha256d(cat(coins.map { le(SEQUENCE, 4) }))
        val outputs = sha256d(cat(outs.map(::ser)))
        return sha256d(
            cat(listOf(le(VERSION, 4), prevouts, sequences, outpoint(coins[i]), compactSize(scriptCode.size), scriptCode,
                le(coins[i].value, 8), le(SEQUENCE, 4), outputs, le(0, 4), le(SIGHASH_ALL.toLong(), 4)))
        )
    }

    fun serialize(coins: List<Coin>, outs: List<TxOut>, witnesses: List<List<ByteArray>>?): ByteArray {
        val p = ArrayList<ByteArray>()
        p += le(VERSION, 4)
        if (witnesses != null) p += byteArrayOf(0, 1)
        p += compactSize(coins.size)
        for (c in coins) { p += outpoint(c); p += compactSize(0); p += le(SEQUENCE, 4) }
        p += compactSize(outs.size)
        for (o in outs) p += ser(o)
        if (witnesses != null) for (w in witnesses) {
            p += compactSize(w.size)
            for (item in w) { p += compactSize(item.size); p += item }
        }
        p += le(0, 4)
        return cat(p)
    }

    fun txid(coins: List<Coin>, outs: List<TxOut>) = sha256d(serialize(coins, outs, null)).reversedArray().hex()

    /** Witness bytes count a quarter: each input carries [sig‖type (2421), 0x00‖pubkey (1313)]. */
    fun vsize(rawSize: Int, inputs: Int): Int {
        val witness = inputs * (1 + 3 + SIG_LEN + 1 + 3 + PUB_LEN + 1) + 2
        val base = rawSize - witness
        return (base * 4 + (rawSize - base) + 3) / 4
    }

    /** Spend `coins` (all owned by `keys`), pay `outputs`, return the rest to the same address. */
    fun build(keys: Keys, coins: List<Coin>, outputs: List<TxOut>, feeRate: Long, deterministic: Boolean = false): BuiltTx {
        require(coins.isNotEmpty()) { "no coins" }
        val spk = scriptFor(keys.program)
        val total = coins.sumOf { it.value }
        val paid = outputs.sumOf { it.value }
        fun make(fee: Long): Pair<ByteArray, List<TxOut>> {
            val change = total - paid - fee
            require(change > DUST) { "not enough SOQ for the fee" }
            val outs = outputs + TxOut(change, spk)
            val wits = coins.indices.map { i ->
                listOf(keys.sign(sighash(coins, outs, i, spk), deterministic) + byteArrayOf(SIGHASH_ALL.toByte()), byteArrayOf(0) + keys.publicKey)
            }
            return serialize(coins, outs, wits) to outs
        }
        val first = make(1200 * feeRate).first
        val fee = vsize(first.size, coins.size) * feeRate
        val (raw, outs) = make(fee)
        return BuiltTx(raw.hex(), txid(coins, outs), fee, vsize(raw.size, coins.size))
    }
}
