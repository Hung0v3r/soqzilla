package com.soqzilla.app.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.math.BigInteger

/** The parts of SPEC.md the app needs: message layout and the proof of work. */
object Rules {
    const val VERSION: Byte = 1
    const val OP_MINT = 0x4d
    const val OP_TRANSFER = 0x54
    const val MEM_KIB = 64 * 1024
    const val CAP = 21_000_000_000L
    val SALT: ByteArray = "soqzilla/v1/pow!".toByteArray(Charsets.US_ASCII)
    private val MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

    /** "SQZ" is the real token; "SQX" is the throwaway test tag. Anything else is refused. */
    fun magicOf(tag: String): ByteArray {
        require(tag == "SQZ" || tag == "SQX") { "unknown token tag $tag" }
        return tag.toByteArray(Charsets.US_ASCII)
    }

    fun head(magic: ByteArray, op: Int) = magic + byteArrayOf(VERSION, op.toByte())

    fun powInput(magic: ByteArray, refHashHex: String, program: ByteArray, nonce: Long) =
        head(magic, OP_MINT) + refHashHex.unhex() + program + be(nonce, 8)

    fun transferPayload(magic: ByteArray, amount: Long, to: ByteArray) = head(magic, OP_TRANSFER) + be(amount, 8) + to

    fun opReturn(payload: ByteArray): ByteArray {
        require(payload.size <= 75)
        return byteArrayOf(0x6a, payload.size.toByte()) + payload
    }

    /** Expected hashes per mint at this target. */
    fun expectedHashes(targetHex: String): Double = MAX.divide(BigInteger(targetHex, 16).max(BigInteger.ONE)).toDouble()
}

/** Argon2id, 64 MiB, 1 pass, 1 lane, 32 bytes. One per mining thread: not thread-safe, and it reuses its output buffer. */
class PowHasher {
    private val gen = Argon2BytesGenerator().apply {
        init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(1)
                .withMemoryAsKB(Rules.MEM_KIB)
                .withParallelism(1)
                .withSalt(Rules.SALT)
                .build()
        )
    }
    private val out = ByteArray(32)

    fun hash(input: ByteArray): ByteArray {
        gen.generateBytes(input, out)
        return out
    }
}
