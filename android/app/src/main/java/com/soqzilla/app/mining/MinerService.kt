package com.soqzilla.app.mining

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.soqzilla.app.R
import com.soqzilla.app.core.PowHasher
import com.soqzilla.app.core.Rules
import com.soqzilla.app.core.isHex
import com.soqzilla.app.core.lessThan
import com.soqzilla.app.core.unhex
import com.soqzilla.app.data.Store
import com.soqzilla.app.net.Api
import com.soqzilla.app.net.ApiException
import com.soqzilla.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Mining runs here, in a foreground service with a visible notification, so it carries on with the
 * screen off. Each thread owns a 64 MiB Argon2 hasher. A found solution goes to the relay, which pays
 * the SOQ fee and broadcasts it; the reward is bound to this phone's address inside the work itself.
 */
class MinerService : Service() {
    private class Work(val magic: ByteArray, val refHeight: Int, val refHash: String, val target: ByteArray, val reward: String, val expected: Double)

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(true)
    @Volatile private var work: Work? = null
    private val hashes = AtomicLong(0)
    private val found = LinkedBlockingQueue<Pair<Work, Long>>()
    private val workers = mutableListOf<Thread>()
    private var scope: CoroutineScope? = null
    private var wake: PowerManager.WakeLock? = null
    private var hot = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMining()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(notification("Starting…"))
        if (!running.getAndSet(true)) startMining()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMining()
        super.onDestroy()
    }

    private fun startMining() {
        // Mining needs only the public address, so it works while the wallet is locked.
        val me = Store.me(this)
        val program = me.programHex.unhex()
        val api = Store.api(this)
        val n = Store.threads(this).coerceIn(1, maxThreads())
        hashes.set(0)
        found.clear()
        MinerState.update { it.copy(running = true, status = "Fetching work…", hashrate = 0.0, hashes = 0, stoppedFor = null, memoryNote = null) }
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soqzilla:mining")
            .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }

        repeat(n) { i ->
            workers += thread(name = "soqzilla-miner-$i", isDaemon = true) { mineLoop(program) }
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s ->
            // Heat has its own loop so a slow network call can never delay a pause or a safety stop.
            s.launch(Dispatchers.Default) { while (isActive && running.get()) { checkHeat(); delay(1_000) } }
            s.launch { workLoop(api) }
            s.launch { statusLoop() }
            s.launch { submitLoop(api, me.address) }
        }
        // ...and Android's battery broadcasts trigger a check the moment the temperature changes.
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryWatch, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(batteryWatch, filter)
        checkHeat()
    }

    private val batteryWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (running.get()) checkHeat() }
    }

    @Volatile private var heatWhy: String? = null
    @Volatile private var netNote: String? = null

    /** Read battery temperature and thermal status; pause, resume or stop. Safe to call from any thread. */
    @Synchronized
    private fun checkHeat() {
        if (!running.get()) return
        val heat = Heat.read(this)
        if (heat != null && heat.mustStop) {
            autoStop(heat.stopReason())
            return
        }
        heatWhy = pauseReason(heat)
        paused.set(heatWhy != null || work == null)
        MinerState.update { it.copy(tempC = heat?.tempC) }
    }

    private fun stopMining(autoReason: String? = null) {
        if (!running.getAndSet(false)) return
        workers.clear() // they see running=false and finish their current hash
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        work = null
        paused.set(true)
        try { unregisterReceiver(batteryWatch) } catch (e: IllegalArgumentException) {}
        MinerState.update { it.copy(running = false, status = if (autoReason != null) "Stopped: $autoReason" else "Stopped", hashrate = 0.0, stoppedFor = autoReason) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val s = scope
        scope = null
        s?.cancel()
    }

    /** Safety stop: end mining, tell the user why, and stay stopped until they start it again. */
    private fun autoStop(reason: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALERTS) == null) {
            nm.createNotificationChannel(NotificationChannel(ALERTS, "Safety stops", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            ALERT_ID,
            Notification.Builder(this, ALERTS)
                .setSmallIcon(R.drawable.ic_stat_mine)
                .setContentTitle("Soqzilla stopped mining")
                .setContentText("The $reason. Let it cool down before mining again.")
                .setStyle(Notification.BigTextStyle().bigText("The $reason, so mining has stopped. Let the phone cool down, then start again from the app."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        stopMining(reason)
        stopSelf()
    }

    private fun mineLoop(program: ByteArray) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            mineLoopInner(program)
        } catch (e: OutOfMemoryError) {
            // Each thread holds 64 MiB on the Java heap. If the phone can't spare it, this thread ends and
            // the others carry on; it must never take the app down.
            MinerState.update { it.copy(memoryNote = "Not enough memory for all threads; mining with fewer. Lower the thread count.") }
        }
    }

    private fun mineLoopInner(program: ByteArray) {
        val hasher = PowHasher()
        val rnd = SecureRandom()
        var current: Work? = null
        var nonce = rnd.nextLong()
        while (running.get()) {
            val w = work
            if (w == null || paused.get()) { Thread.sleep(500); continue }
            if (w !== current) { current = w; nonce = rnd.nextLong() }
            nonce++
            val h = hasher.hash(Rules.powInput(w.magic, w.refHash, program, nonce))
            hashes.incrementAndGet()
            if (lessThan(h, w.target)) found.offer(w to nonce)
        }
    }

    /** Fetch fresh work every 20 s. Network only: heat is handled by checkHeat(). */
    private suspend fun workLoop(api: Api) {
        while (currentCoroutineContext().isActive && running.get()) {
            netNote = try {
                val j = api.work()
                if (j.optBoolean("ready")) {
                    val t = j.getString("target")
                    val ref = j.getString("refHash")
                    require(t.isHex(64) && ref.isHex(64)) { "bad work from server" }
                    val cur = work
                    if (cur == null || cur.refHeight != j.getInt("refHeight") || cur.target.size != 32 || !cur.target.contentEquals(t.unhex())) {
                        work = Work(Rules.magicOf(j.getString("magic")), j.getInt("refHeight"), ref, t.unhex(), j.optString("reward"), Rules.expectedHashes(t))
                    }
                    null
                } else {
                    work = null
                    val lh = j.optInt("launchHeight", -1)
                    if (lh > 0) "Launches at SOQ block $lh" else j.optString("reason", "Not ready")
                }
            } catch (e: Exception) {
                "Can't reach the server: ${e.message ?: e.javaClass.simpleName}"
            }
            paused.set(heatWhy != null || work == null)
            delay(20_000)
        }
    }

    /** Hash rate, status line and notification, every 2 s. */
    private suspend fun statusLoop() {
        var lastCount = 0L
        var lastT = SystemClock.elapsedRealtime()
        var rate = 0.0
        var lastNotify = 0L
        while (currentCoroutineContext().isActive && running.get()) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastT) / 1000.0
            if (dt >= 3) {
                val c = hashes.get()
                val r = (c - lastCount) / dt
                rate = if (rate == 0.0) r else 0.6 * rate + 0.4 * r
                lastCount = c
                lastT = now
            }
            val status = heatWhy ?: netNote ?: if (work == null) "Fetching work…" else "Mining"
            val w = work
            MinerState.update { it.copy(status = status, hashrate = if (paused.get()) 0.0 else rate, hashes = hashes.get(), refHeight = w?.refHeight, expectedHashes = w?.expected, reward = w?.reward) }
            if (now - lastNotify > 5_000) {
                lastNotify = now
                val ui = MinerState.ui.value
                val temp = ui.tempC?.let { " · %.0f°C".format(it) } ?: ""
                val text = if (status == "Mining") "Mining · ${"%.1f".format(rate)} H/s · ${ui.accepted} found$temp" else status
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
            delay(2_000)
        }
    }

    private suspend fun submitLoop(api: Api, address: String) {
        while (currentCoroutineContext().isActive && running.get()) {
            val (w, nonce) = found.poll(1, TimeUnit.SECONDS) ?: continue
            MinerState.update { it.copy(found = it.found + 1) }
            // A find stays good for ~16 SOQ blocks, so "slow down", a busy relay or a network blip are retried
            // for up to two minutes; only a definite rejection (wrong work, too old, duplicate) gives up.
            var sub: Submission? = null
            for (attempt in 0 until 24) {
                sub = try {
                    val r = api.mint(w.refHeight, nonce, address)
                    Submission(System.currentTimeMillis(), w.refHeight, r.optString("txid"), null, r.optString("reward"))
                } catch (e: ApiException) {
                    if (e.status == 429 || e.status == 503 || e.status >= 500) null
                    else Submission(System.currentTimeMillis(), w.refHeight, null, e.message, null)
                } catch (e: Exception) {
                    null
                }
                if (sub != null || !running.get()) break
                delay(5_000)
            }
            val s = sub ?: Submission(System.currentTimeMillis(), w.refHeight, null, "relay unreachable", null)
            MinerState.update { it.copy(accepted = it.accepted + if (s.txid != null) 1 else 0, submissions = (listOf(s) + it.submissions).take(30)) }
        }
    }

    /** Null to mine, or why not right now. */
    private fun pauseReason(h: Heat.Reading?): String? {
        if (h == null) return null
        if (Store.chargingOnly(this) && !h.plugged) return "Waiting for a charger"
        if (!h.plugged && h.level < 25) return "Battery below 25%"
        // pause at 42°C (or severe thermal status), resume once back under 38°C
        hot = if (hot) h.tempC >= Heat.RESUME_C || h.tooHot else h.tooHot
        if (hot) return "Cooling down (battery ${"%.0f".format(h.tempC)}°C)"
        return null
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mining", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MinerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mine)
            .setContentTitle("Soqzilla")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.soqzilla.app.STOP"
        private const val CHANNEL = "mining"
        private const val NOTIFICATION_ID = 7
        private const val ALERTS = "alerts"
        private const val ALERT_ID = 8

        /** Threads this phone can run: up to 4 cores, and no more than the Java heap holds at ~72 MB each. */
        fun maxThreads(): Int {
            val byHeap = ((Runtime.getRuntime().maxMemory() - 64L * 1024 * 1024) / (72L * 1024 * 1024)).toInt()
            return minOf(Runtime.getRuntime().availableProcessors(), 4, byHeap).coerceAtLeast(1)
        }

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, MinerService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, MinerService::class.java).setAction(ACTION_STOP))
    }
}
