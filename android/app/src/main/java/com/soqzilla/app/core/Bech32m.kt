package com.soqzilla.app.core

/** BIP-350 bech32m for witness-v1 programs: the sq1p… addresses Soqucoin uses. */
object Bech32m {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val CONST = 0x2bc830a3
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        IntArray(hrp.length) { hrp[it].code ushr 5 } + intArrayOf(0) + IntArray(hrp.length) { hrp[it].code and 31 }

    private fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (v in data) {
            if (v < 0 || v ushr from != 0) return null
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) return null
        return out.toIntArray()
    }

    fun encode(hrp: String, program: ByteArray): String {
        val data = intArrayOf(1) + convertBits(IntArray(program.size) { program[it].toInt() and 0xff }, 8, 5, true)!!
        val mod = polymod(hrpExpand(hrp) + data + IntArray(6)) xor CONST
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** The 32-byte program of a witness-v1 address with this prefix, or null if it is not one. */
    fun decode(hrp: String, address: String): ByteArray? {
        val a = address.trim()
        if (a != a.lowercase() && a != a.uppercase()) return null
        val s = a.lowercase()
        val pos = s.lastIndexOf('1')
        if (pos < 1 || pos + 7 > s.length || s.substring(0, pos) != hrp) return null
        val data = IntArray(s.length - pos - 1) { CHARSET.indexOf(s[pos + 1 + it]) }
        if (data.any { it < 0 }) return null
        if (polymod(hrpExpand(hrp) + data) != CONST) return null
        val words = data.copyOfRange(0, data.size - 6)
        if (words.isEmpty() || words[0] != 1) return null
        val prog = convertBits(words.copyOfRange(1, words.size), 5, 8, false) ?: return null
        if (prog.size != 32) return null
        return ByteArray(32) { prog[it].toByte() }
    }
}
