package com.soqzilla.app.net

import com.soqzilla.app.core.Coin
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, message: String) : Exception(message)

/**
 * The Soqzilla indexer's JSON API. Blocking: call from Dispatchers.IO.
 * Tries each server in order (soqzilla.com first, then the Railway address) and moves on when one is
 * unreachable or answers with something that is not the indexer (a host's own 404 page, a proxy error).
 * The indexer's own errors always carry an "error" field and are reported, not retried elsewhere.
 */
class Api(bases: List<String>) {
    private val bases = bases.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

    private fun ordered(): List<String> {
        val now = System.currentTimeMillis()
        val (bad, good) = bases.partition { (failedAt[it] ?: 0L) > now - 5 * 60_000 }
        return good + bad
    }

    private fun once(base: String, method: String, path: String, body: JSONObject?): Pair<Int, JSONObject?> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8_000
            c.readTimeout = 15_000
            c.setRequestProperty("user-agent", "soqzilla-android/1")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to (try { JSONObject(text) } catch (e: Exception) { null })
        } finally {
            c.disconnect()
        }
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        var last: Exception = IOException("no server configured")
        for (base in ordered()) {
            try {
                val (code, j) = once(base, method, path, body)
                val ours = j != null && (code < 400 || j.has("error"))
                if (!ours) {
                    failedAt[base] = System.currentTimeMillis()
                    last = IOException("server at $base answered HTTP $code")
                    continue
                }
                failedAt.remove(base)
                if (code >= 400) throw ApiException(code, j!!.optString("error", "HTTP $code"))
                return j!!
            } catch (e: IOException) {
                failedAt[base] = System.currentTimeMillis()
                last = e
            }
        }
        throw last
    }

    fun status() = call("GET", "/api/status")
    fun work() = call("GET", "/api/work")
    fun address(a: String) = call("GET", "/api/address/$a")
    fun top() = call("GET", "/api/top?limit=50")

    fun mint(refHeight: Int, nonce: Long, address: String) = call(
        "POST", "/api/mint",
        JSONObject().put("refHeight", refHeight).put("nonce", java.lang.Long.toUnsignedString(nonce)).put("address", address)
    )

    fun gas(address: String) = call("POST", "/api/gas", JSONObject().put("address", address))

    fun utxos(address: String): List<Coin> {
        val a = call("GET", "/api/utxos/$address").getJSONArray("utxos")
        return (0 until a.length()).map {
            val u = a.getJSONObject(it)
            Coin(u.getString("txid"), u.getInt("vout"), u.getString("value").toLong())
        }
    }

    fun broadcast(rawHex: String): String = call("POST", "/api/broadcast", JSONObject().put("rawTxHex", rawHex)).getString("txid")

    companion object {
        /** Servers that failed recently, shared by every Api instance in the app. */
        private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
