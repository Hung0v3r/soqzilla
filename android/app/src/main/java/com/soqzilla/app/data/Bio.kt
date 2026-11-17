package com.soqzilla.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint (or strong face) unlock. A Keystore AES key that only works inside a successful strong
 * biometric prompt wraps the passphrase-derived vault key; the passphrase itself is never stored.
 * Enrolling a new fingerprint kills the key, and the passphrase is needed again.
 */
object Bio {
    private const val ALIAS = "soqzilla-bio"
    private const val PREF = "bio"

    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun enabled(ctx: Context): Boolean = Store.prefs(ctx).contains(PREF)

    fun disable(ctx: Context) {
        Store.prefs(ctx).edit().remove(PREF).apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun newKey(): SecretKey {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else {
            @Suppress("DEPRECATION")
            b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(b.build()); generateKey() }
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    /** Turn it on: one prompt, then the vault key is stored wrapped. */
    fun enable(activity: FragmentActivity, vaultKey: ByteArray, done: (String?) -> Unit) {
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        } catch (e: Exception) {
            done("Fingerprint unlock could not be set up: ${e.message}"); return
        }
        prompt(activity, "Turn on fingerprint unlock", cipher, { c ->
            try {
                val blob = c.iv + c.doFinal(vaultKey)
                Store.prefs(activity).edit().putString(PREF, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
                done(null)
            } catch (e: Exception) {
                done("Fingerprint unlock could not be set up: ${e.message}")
            }
        }, { msg -> done(msg ?: "Fingerprint unlock was not turned on.") })
    }

    /** Unlock: the prompt opens the wrapped vault key. Every failure falls back to the passphrase. */
    fun unlock(activity: FragmentActivity, done: (vaultKey: ByteArray?, error: String?) -> Unit) {
        val stored = Store.prefs(activity).getString(PREF, null) ?: return done(null, "Fingerprint unlock is off")
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = try {
            val key = existingKey() ?: throw KeyPermanentlyInvalidatedException()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable(activity)
            return done(null, "Your fingerprints changed, so fingerprint unlock was turned off. Use the passphrase.")
        } catch (e: Exception) {
            return done(null, "Fingerprint unlock is not available: ${e.message}")
        }
        prompt(activity, "Unlock Soqzilla", cipher, { c ->
            try { done(c.doFinal(blob.copyOfRange(12, blob.size)), null) } catch (e: Exception) { done(null, "Fingerprint unlock failed. Use the passphrase.") }
        }, { msg -> done(null, msg) })
    }

    private fun prompt(activity: FragmentActivity, title: String, cipher: Cipher, ok: (Cipher) -> Unit, fail: (String?) -> Unit) {
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) ok(c) else fail("Fingerprint unlock failed. Use the passphrase.")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                fail(if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) null else errString.toString())
            }
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText("Use passphrase")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }
}
