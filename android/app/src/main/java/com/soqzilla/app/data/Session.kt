package com.soqzilla.app.data

import android.os.SystemClock
import com.soqzilla.app.core.Keys
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The unlocked wallet, in memory only. Mining never needs it (it uses the public address); sending and
 * the backup code do. Locking drops the key and wipes the seed bytes.
 */
object Session {
    private var keys: Keys? = null
    val unlocked = MutableStateFlow(false)
    @Volatile var lastActivity: Long = SystemClock.elapsedRealtime()
        private set

    @Synchronized
    fun open(k: Keys) {
        if (keys !== k) keys?.seed?.fill(0)
        keys = k
        touch()
        unlocked.value = true
    }

    @Synchronized
    fun keys(): Keys? = keys

    @Synchronized
    fun lock() {
        keys?.seed?.fill(0)
        keys = null
        unlocked.value = false
    }

    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
    }
}
