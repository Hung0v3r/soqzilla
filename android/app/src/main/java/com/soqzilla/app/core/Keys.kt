package com.soqzilla.app.core

import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import java.security.SecureRandom

/** An ML-DSA-44 key from a 32-byte seed (FIPS 204 key generation, same as the Soqucoin SDK). */
class Keys(val seed: ByteArray) {
    init {
        require(seed.size == 32) { "seed must be 32 bytes" }
    }

    private val priv = MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_44, seed)
    val publicKey: ByteArray = priv.publicKey
    val program: ByteArray = sha256(publicKey)
    val address: String = Bech32m.encode(HRP, program)

    /** The SDK refuses keys whose public key starts with 0xff; so do we. */
    val spendable: Boolean get() = (publicKey[0].toInt() and 0xff) != 0xff

    fun sign(message: ByteArray, deterministic: Boolean = false): ByteArray {
        val s = MLDSASigner()
        s.init(true, if (deterministic) priv else ParametersWithRandom(priv, SecureRandom()))
        s.update(message, 0, message.size)
        return s.generateSignature()
    }

    companion object {
        const val HRP = "sq"

        fun generate(): Keys {
            val r = SecureRandom()
            while (true) {
                val k = Keys(ByteArray(32).also { r.nextBytes(it) })
                if (k.spendable) return k
            }
        }
    }
}
