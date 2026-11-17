package com.soqzilla.app.data

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The passphrase layer. key = Argon2id(passphrase, salt, 64 MiB, 3 passes); the 32-byte seed is sealed with
 * AES-256-GCM under it. Blob = salt (16) ‖ iv (12) ‖ ciphertext+tag. Store wraps the blob once more with a
 * device-bound Keystore key, so a copied file cannot even be guessed at away from the phone.
 */
object Vault {
    const val MIN_LENGTH = 10
    private const val MEM_KIB = 64 * 1024
    private const val PASSES = 3
    private const val SALT = 16
    private const val IV = 12

    private val rnd = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT).also { rnd.nextBytes(it) }
    fun saltOf(blob: ByteArray): ByteArray = blob.copyOfRange(0, SALT)

    /** Slow on purpose (about half a second on a phone): call from a background thread. */
    fun deriveKey(pass: CharArray, salt: ByteArray): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(PASSES)
                .withMemoryAsKB(MEM_KIB)
                .withParallelism(1)
                .withSalt(salt)
                .build()
        )
        val out = ByteArray(32)
        gen.generateBytes(pass, out)
        return out
    }

    fun seal(seed: ByteArray, key: ByteArray, salt: ByteArray): ByteArray {
        val iv = ByteArray(IV).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return salt + iv + c.doFinal(seed)
    }

    /** The seed, or null when the key is wrong (the GCM tag does not check out). */
    fun open(blob: ByteArray, key: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(SALT, SALT + IV)))
        c.doFinal(blob.copyOfRange(SALT + IV, blob.size))
    } catch (e: GeneralSecurityException) {
        null
    }
}
