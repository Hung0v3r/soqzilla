package com.soqzilla.app.core

import java.security.MessageDigest

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.hex(): String {
    val c = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        c[2 * i] = HEX[v ushr 4]
        c[2 * i + 1] = HEX[v and 15]
    }
    return String(c)
}

fun String.isHex(len: Int? = null) = (len == null || length == len) && length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

fun String.unhex(): ByteArray {
    require(isHex()) { "not hex" }
    return ByteArray(length / 2) { i -> ((Character.digit(this[2 * i], 16) shl 4) or Character.digit(this[2 * i + 1], 16)).toByte() }
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

fun sha256d(b: ByteArray): ByteArray = sha256(sha256(b))

/** n bytes, big-endian. */
fun be(v: Long, n: Int) = ByteArray(n) { i -> (v ushr (8 * (n - 1 - i))).toByte() }

/** n bytes, little-endian. */
fun le(v: Long, n: Int) = ByteArray(n) { i -> (v ushr (8 * i)).toByte() }

fun compactSize(n: Int): ByteArray = when {
    n < 0xfd -> byteArrayOf(n.toByte())
    n <= 0xffff -> byteArrayOf(0xfd.toByte()) + le(n.toLong(), 2)
    else -> byteArrayOf(0xfe.toByte()) + le(n.toLong(), 4)
}

/** Unsigned big-endian a < b for equal-length arrays. */
fun lessThan(a: ByteArray, b: ByteArray): Boolean {
    for (i in a.indices) {
        val x = a[i].toInt() and 0xff
        val y = b[i].toInt() and 0xff
        if (x != y) return x < y
    }
    return false
}
