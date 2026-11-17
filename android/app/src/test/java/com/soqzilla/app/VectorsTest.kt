package com.soqzilla.app

import com.soqzilla.app.core.Bech32m
import com.soqzilla.app.core.Coin
import com.soqzilla.app.core.Keys
import com.soqzilla.app.core.PowHasher
import com.soqzilla.app.core.Rules
import com.soqzilla.app.core.Tx
import com.soqzilla.app.core.TxOut
import com.soqzilla.app.core.hex
import com.soqzilla.app.core.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Known answers from scripts/make-vectors.mjs (the Node reference and soq-lightning-sdk). */
class VectorsTest {
    private val v = javaClass.classLoader!!.getResource("vectors.json")!!.readText()

    /** "a.b": the value of key b after key a. Enough for the flat vectors file. */
    private fun get(path: String): String {
        val parts = path.split(".")
        var from = 0
        for (p in parts.dropLast(1)) from = v.indexOf("\"$p\"", from)
        val m = Regex("\"${parts.last()}\":\\s*\"?([^\",}\\s]*)").find(v, from)!!
        return m.groupValues[1]
    }

    private val keys = Keys(get("seed").unhex())

    @Test
    fun keysMatchTheSdk() {
        assertEquals(get("publicKey"), keys.publicKey.hex())
        assertEquals(get("program"), keys.program.hex())
        assertEquals(get("address"), keys.address)
        assertTrue(keys.spendable)
    }

    @Test
    fun bech32mRoundTrips() {
        assertArrayEquals(get("program").unhex(), Bech32m.decode("sq", get("address")))
        val a = get("address")
        val broken = a.dropLast(1) + (if (a.last() == 'q') 'p' else 'q')
        assertNull(Bech32m.decode("sq", broken))
        assertNull(Bech32m.decode("ssq", a))
    }

    @Test
    fun deterministicSignatureMatchesNoble() {
        assertEquals(get("sign.signature"), keys.sign(get("sign.message").unhex(), deterministic = true).hex())
    }

    @Test
    fun proofOfWorkMatchesTheIndexer() {
        val input = Rules.powInput(Rules.magicOf("SQZ"), get("pow.refHash"), keys.program, get("pow.nonce").toLong())
        assertEquals(get("pow.input"), input.hex())
        val h = PowHasher()
        assertEquals(get("pow.hash"), h.hash(input).hex())
        assertEquals("reusing the hasher gives the same answer", get("pow.hash"), h.hash(input).hex())
    }

    @Test
    fun transferMatchesTheSdkByteForByte() {
        val payload = Rules.transferPayload(Rules.magicOf("SQZ"), 10_000, get("recipientProgram").unhex())
        assertEquals(get("transfer.payload"), payload.hex())
        val coin = Coin(get("transfer.coinTxid"), get("transfer.coinVout").toInt(), get("transfer.coinValue").toLong())
        val outs = listOf(TxOut(0, Rules.opReturn(payload)))
        val built = Tx.build(keys, listOf(coin), outs, get("transfer.feeRate").toLong(), deterministic = true)
        assertEquals(get("transfer.vsize").toInt(), built.vsize)
        assertEquals(get("transfer.fee").toLong(), built.fee)
        assertEquals(get("transfer.rawTx"), built.rawHex)
        assertEquals(get("transfer.spendTxid"), built.txid)
    }
}
