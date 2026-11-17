package com.soqzilla.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.soqzilla.app.BuildConfig
import com.soqzilla.app.core.Keys
import com.soqzilla.app.core.hex
import com.soqzilla.app.net.Api
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Settings and the wallet.
 *
 * Public identity ("address", "program") is stored readable so mining works while the wallet is locked.
 * Secrets are wrapped with an AES key that never leaves the Android Keystore; on top of that the seed is
 * sealed under the user's passphrase ("vault", see [Vault]). Wallets made before passphrases existed keep
 * a device-only "seed" until the user sets one, and cannot send or show the backup code until then.
 * Cloud backup is off in the manifest, so the backup code is the only way to move a wallet.
 */
object Store {
    private const val PREFS = "soqzilla"
    private const val KEY_ALIAS = "soqzilla-wallet"

    fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- device layer ----

    private fun deviceKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    private fun wrap(bytes: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deviceKey())
        return Base64.encodeToString(c.iv + c.doFinal(bytes), Base64.NO_WRAP)
    }

    private fun unwrap(s: String): ByteArray {
        val raw = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return c.doFinal(raw.copyOfRange(12, raw.size))
    }

    // ---- the wallet ----

    /** What can be shown and mined to without the passphrase. */
    data class Me(val address: String, val programHex: String)

    @Synchronized
    fun me(ctx: Context): Me {
        val p = prefs(ctx)
        val a = p.getString("address", null)
        val prog = p.getString("program", null)
        if (a != null && prog != null) return Me(a, prog)
        // Installs from before 1.1 only have the device-only seed: derive the address once and keep it.
        val seed = try {
            p.getString("seed", null)?.let { unwrap(it) }
        } catch (e: Exception) {
            setAsideUnreadable(ctx)
            null
        }
        val k = seed?.let { Keys(it) } ?: Keys.generate().also { p.edit().putString("seed", wrap(it.seed)).putBoolean("backedUp", false).commit() }
        check(p.edit().putString("address", k.address).putString("program", k.program.hex()).commit()) { "could not save the wallet" }
        return Me(k.address, k.program.hex())
    }

    /** The Keystore key is gone (data copied to a new phone): keep every unreadable copy, start fresh, say so. */
    private fun setAsideUnreadable(ctx: Context) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        p.edit().apply {
            p.getString("seed", null)?.let { putString("seed.unreadable.$now", it) }
            p.getString("vault", null)?.let { putString("vault.unreadable.$now", it) }
        }.remove("seed").remove("vault").remove("address").remove("program")
            .putBoolean("backedUp", false).putBoolean("walletReplaced", true).commit()
        Bio.disable(ctx)
    }

    fun hasPassphrase(ctx: Context): Boolean = prefs(ctx).contains("vault")

    /** The keys of a wallet that has no passphrase yet (only to set one). */
    fun unprotectedKeys(ctx: Context): Keys? =
        if (hasPassphrase(ctx)) null else prefs(ctx).getString("seed", null)?.let { Keys(unwrap(it)) }

    class Unlocked(val keys: Keys, val vaultKey: ByteArray)

    /** Slow (Argon2id): call off the main thread. Null when the passphrase is wrong. */
    fun unlock(ctx: Context, pass: CharArray): Unlocked? {
        val blob = vaultBlob(ctx)
        val key = Vault.deriveKey(pass, Vault.saltOf(blob))
        val seed = Vault.open(blob, key) ?: return null
        return Unlocked(Keys(seed), key)
    }

    /** With the vault key from fingerprint unlock. */
    fun unlockWithKey(ctx: Context, vaultKey: ByteArray): Keys? = Vault.open(vaultBlob(ctx), vaultKey)?.let { Keys(it) }

    private fun vaultBlob(ctx: Context): ByteArray {
        val s = prefs(ctx).getString("vault", null) ?: error("no passphrase is set")
        return try {
            unwrap(s)
        } catch (e: Exception) {
            error("this phone's key store can't open the wallet any more; restore it from its backup code")
        }
    }

    /** Seal the seed under a (new) passphrase. Turns fingerprint unlock off. Slow: off the main thread. */
    @Synchronized
    fun setPassphrase(ctx: Context, seed: ByteArray, pass: CharArray): ByteArray {
        require(pass.size >= Vault.MIN_LENGTH) { "use at least ${Vault.MIN_LENGTH} characters" }
        val salt = Vault.newSalt()
        val key = Vault.deriveKey(pass, salt)
        val blob = Vault.seal(seed, key, salt)
        check(prefs(ctx).edit().putString("vault", wrap(blob)).remove("seed").commit()) { "could not save the wallet" }
        Bio.disable(ctx)
        return key
    }

    /** Replace the wallet with one from a backup code, sealed under [pass] (required once a passphrase exists). */
    @Synchronized
    fun restore(ctx: Context, seed: ByteArray, pass: CharArray?): Keys {
        val k = Keys(seed)
        require(k.spendable) { "this backup code gives an unusable key" }
        if (pass != null) setPassphrase(ctx, seed.copyOf(), pass)
        else check(prefs(ctx).edit().putString("seed", wrap(seed)).remove("vault").commit()) { "could not save the wallet" }
        prefs(ctx).edit()
            .putString("address", k.address).putString("program", k.program.hex())
            .putBoolean("backedUp", true).remove("walletReplaced").remove("recipients")
            .commit()
        return k
    }

    /** Remove the wallet from this phone. A new, empty one is made the next time the app needs an address. */
    @Synchronized
    fun deleteWallet(ctx: Context) {
        Bio.disable(ctx)
        prefs(ctx).edit()
            .remove("seed").remove("vault").remove("address").remove("program")
            .remove("backedUp").remove("walletReplaced").remove("recipients")
            .commit()
    }

    // ---- recipients (for first-time and look-alike checks; addresses are public) ----

    fun recipients(ctx: Context): Set<String> = prefs(ctx).getStringSet("recipients", emptySet()) ?: emptySet()
    fun addRecipient(ctx: Context, address: String) =
        prefs(ctx).edit().putStringSet("recipients", recipients(ctx) + address).apply()

    // ---- server ----

    /** A server the user picked: only honoured in builds that have the server setting (not the launch build). */
    private fun chosenServer(ctx: Context): String? = if (BuildConfig.SERVER_SETTING) prefs(ctx).getString("apiUrl", null) else null

    fun apiUrl(ctx: Context): String = chosenServer(ctx) ?: BuildConfig.API_URL

    /** The chosen server, or soqzilla.com with the Railway address as fallback. */
    fun api(ctx: Context): Api = Api(chosenServer(ctx)?.let { listOf(it) } ?: listOf(BuildConfig.API_URL, BuildConfig.API_FALLBACK_URL))
    fun setApiUrl(ctx: Context, url: String?) = prefs(ctx).edit().apply { if (url.isNullOrBlank()) remove("apiUrl") else putString("apiUrl", url.trim()) }.apply()

    // ---- settings ----

    fun threads(ctx: Context): Int = prefs(ctx).getInt("threads", 2)
    fun setThreads(ctx: Context, n: Int) = prefs(ctx).edit().putInt("threads", n).apply()

    fun chargingOnly(ctx: Context): Boolean = prefs(ctx).getBoolean("chargingOnly", false)
    fun setChargingOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("chargingOnly", on).apply()

    /** Lock after this many minutes without a tap (1–60). */
    fun autoLockMinutes(ctx: Context): Int = prefs(ctx).getInt("autoLockMinutes", 5)
    fun setAutoLockMinutes(ctx: Context, m: Int) = prefs(ctx).edit().putInt("autoLockMinutes", m.coerceIn(1, 60)).apply()

    /** Lock 60 s after the app leaves the screen. */
    fun lockOnLeave(ctx: Context): Boolean = prefs(ctx).getBoolean("lockOnLeave", true)
    fun setLockOnLeave(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("lockOnLeave", on).apply()

    /** Clear the clipboard 60 s after copying an address. */
    fun clipboardWipe(ctx: Context): Boolean = prefs(ctx).getBoolean("clipboardWipe", true)
    fun setClipboardWipe(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("clipboardWipe", on).apply()

    /** Block screenshots of the whole app (the backup code is always blocked). */
    fun blockCapture(ctx: Context): Boolean = prefs(ctx).getBoolean("blockCapture", false)
    fun setBlockCapture(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("blockCapture", on).apply()

    /** "system", "light" or "dark". */
    fun theme(ctx: Context): String = prefs(ctx).getString("theme", "system") ?: "system"
    fun setTheme(ctx: Context, t: String) = prefs(ctx).edit().putString("theme", t).apply()

    /** True when the old wallet could not be unlocked on this phone and a new one was made. */
    fun walletReplaced(ctx: Context): Boolean = prefs(ctx).getBoolean("walletReplaced", false)

    /** The user has read and accepted the heat and battery notice. */
    fun heatNoticeAccepted(ctx: Context): Boolean = prefs(ctx).getBoolean("heatNotice2", false)
    fun acceptHeatNotice(ctx: Context) = prefs(ctx).edit().putBoolean("heatNotice2", true).apply()

    fun backedUp(ctx: Context): Boolean = prefs(ctx).getBoolean("backedUp", false)
    fun setBackedUp(ctx: Context) = prefs(ctx).edit().putBoolean("backedUp", true).apply()
}
