package com.soqzilla.app

import com.soqzilla.app.data.Vault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The passphrase layer on its own (the Keystore layer around it needs a phone). */
class VaultTest {
    private val seed = ByteArray(32) { it.toByte() }

    @Test
    fun sealsAndOpensWithTheRightPassphraseOnly() {
        val salt = Vault.newSalt()
        val key = Vault.deriveKey("correct horse battery".toCharArray(), salt)
        val blob = Vault.seal(seed, key, salt)
        assertArrayEquals(salt, Vault.saltOf(blob))
        val again = Vault.deriveKey("correct horse battery".toCharArray(), Vault.saltOf(blob))
        assertArrayEquals(seed, Vault.open(blob, again))
        val wrong = Vault.deriveKey("correct horse batterz".toCharArray(), Vault.saltOf(blob))
        assertNull(Vault.open(blob, wrong))
    }

    @Test
    fun tamperingIsCaught() {
        val salt = Vault.newSalt()
        val key = Vault.deriveKey("another passphrase".toCharArray(), salt)
        val blob = Vault.seal(seed, key, salt)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertNull(Vault.open(blob, key))
    }

    @Test
    fun freshSaltAndNonceEveryTime() {
        val key = ByteArray(32) { 7 }
        val a = Vault.seal(seed, key, Vault.newSalt())
        val b = Vault.seal(seed, key, Vault.newSalt())
        assertNotEquals(a.toList(), b.toList())
    }
}
