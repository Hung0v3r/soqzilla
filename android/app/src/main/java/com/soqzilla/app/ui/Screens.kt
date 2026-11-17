package com.soqzilla.app.ui

import android.Manifest
import android.app.Activity
import android.os.PersistableBundle
import android.view.WindowManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soqzilla.app.BuildConfig
import com.soqzilla.app.core.Bech32m
import com.soqzilla.app.core.Keys
import com.soqzilla.app.core.Rules
import com.soqzilla.app.core.Tx
import com.soqzilla.app.core.TxOut
import com.soqzilla.app.core.hex
import com.soqzilla.app.core.isHex
import com.soqzilla.app.core.unhex
import com.soqzilla.app.data.Store
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.soqzilla.app.data.Bio
import com.soqzilla.app.data.Session
import com.soqzilla.app.data.Vault
import com.soqzilla.app.mining.Heat
import com.soqzilla.app.mining.MinerService
import com.soqzilla.app.mining.MinerState
import com.soqzilla.app.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val FEE_RATE = 1500L
private const val FIND_FEE_SHORS = 1086 * FEE_RATE // one relayed find: ~1,086 vB at 1,500 shors/vB

// ---------- shared bits ----------

private fun sqz(v: String?): String = v?.toLongOrNull()?.let { "%,d".format(it) } ?: "–"
private fun soq(shors: Long): String = "%.4f".format(shors / 1e8)
private fun short(a: String) = if (a.length > 20) a.take(10) + "…" + a.takeLast(6) else a

private fun duration(seconds: Double): String = when {
    seconds.isNaN() || seconds.isInfinite() -> "–"
    seconds < 90 -> "%.0f s".format(seconds)
    seconds < 5400 -> "%.0f min".format(seconds / 60)
    seconds < 172_800 -> "%.1f h".format(seconds / 3600)
    else -> "%.1f days".format(seconds / 86_400)
}

/** A section: a heavy 2px rule, then "[01] TITLE" in mono with the number in signal orange. */
@Composable
private fun Panel(title: String? = null, index: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(2.dp, Rule).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Row {
            if (index != null) Text("[%02d] ".format(index), color = Signal, style = MaterialTheme.typography.labelMedium)
            Text(title.uppercase(), color = Fg, style = MaterialTheme.typography.labelMedium)
        }
        content()
    }
}

/** Spec row: small uppercase mono label on the left, the value on the right. */
@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.weight(1f, fill = false).padding(end = 12.dp), color = Mute, style = MaterialTheme.typography.labelSmall)
        Text(value, fontFamily = Mono, fontSize = 13.sp, color = Fg)
    }
}

/** Solid button: signal orange, square (Material's buttons are pills unless told otherwise). */
@Composable
private fun Solid(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier, enabled, shape = RectangleShape, content = content)

/** Outline button: 2px ink border, ink text (the secondary button). */
@Composable
private fun Ghost(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(
        onClick, modifier, enabled,
        shape = RectangleShape,
        border = BorderStroke(2.dp, if (enabled) Rule else Mute),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Fg),
        content = content,
    )

@Composable
private fun Screen(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Hex-dump strip between 2px rules, then the heading: heavy, fully wide, uppercase, with a blinking cursor. */
@Composable
private fun Title(text: String, eyebrow: String? = null) = Column {
    if (eyebrow != null) {
        HorizontalDivider(thickness = 2.dp, color = Rule)
        Text(eyebrow.uppercase(), Modifier.padding(vertical = 6.dp), color = Mute, style = MaterialTheme.typography.labelMedium)
        HorizontalDivider(thickness = 2.dp, color = Rule)
        Spacer(Modifier.height(10.dp))
    }
    val blink = rememberInfiniteTransition(label = "cursor")
    val on by blink.animateFloat(1f, 0f, infiniteRepeatable(tween(1000, easing = { if (it < 0.5f) 0f else 1f })), label = "cursor")
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text.uppercase(), fontFamily = Display, fontSize = 34.sp, lineHeight = 34.sp, color = Fg)
        Box(Modifier.padding(start = 4.dp, bottom = 6.dp).size(width = 22.dp, height = 7.dp).alpha(on).background(Signal))
    }
}

/** The project's public contact: the site and the Telegram handle. */
@Composable
private fun ContactLine() {
    val ctx = LocalContext.current
    val open = { url: String -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }; Unit }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton({ open("https://soqzilla.com") }) { Text("soqzilla.com", color = Mute, fontFamily = Mono, fontSize = 12.sp) }
        TextButton({ open("https://t.me/Hung0v3r") }) { Text("Telegram @Hung0v3r", color = Mute, fontFamily = Mono, fontSize = 12.sp) }
    }
}

/** The bytes of a word as a hex dump: "SOQZILLA" -> "53 4F 51 5A 49 4C 4C 41". */
private fun hexOf(word: String) = word.uppercase().toByteArray().joinToString(" ") { "%02X".format(it) }

/** Poll a call every [everyMs] while the screen is shown. */
@Composable
private fun <T> polled(everyMs: Long, key: Any? = Unit, fetch: suspend () -> T): T? {
    var value by remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) {
        while (true) {
            runCatching { withContext(Dispatchers.IO) { fetch() } }.onSuccess { value = it }
            delay(everyMs)
        }
    }
    return value
}

// ---------- Mine ----------

@Composable
fun MineScreen(me: Store.Me, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ui by MinerState.ui.collectAsState()
    val status = polled(20_000) { Store.api(ctx).status() }
    var threads by remember { mutableFloatStateOf(Store.threads(ctx).toFloat()) }
    var chargingOnly by remember { mutableStateOf(Store.chargingOnly(ctx)) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { MinerService.start(ctx) }
    val maxT = MinerService.maxThreads()
    var heat by remember { mutableStateOf(Heat.read(ctx)) }
    var showHeatNotice by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { heat = Heat.read(ctx); delay(3_000) }
    }

    Screen(modifier) {
        Title("Soqzilla", eyebrow = hexOf("SOQZILLA") + "  ·  SQZ")
        Text("Mined on phones. Recorded on Soqucoin.", color = Mute)

        HeatWarning(heat, ui.stoppedFor)

        Panel("Network", 1) { NetworkInfo(status) }

        Panel("This phone", 2) {
            Text(if (ui.running) "%.1f H/s".format(ui.hashrate) else "Idle", fontSize = 36.sp, fontFamily = Display, color = Fg)
            Text(ui.status, color = if (ui.status == "Mining") Signal else Mute)
            ui.expectedHashes?.let { e ->
                if (ui.running && ui.hashrate > 0) Line("Typical time to find one", duration(e / ui.hashrate))
            }
            ui.memoryNote?.let { Text(it, color = Warn, fontSize = 13.sp) }
            Line("Hashes this session", "%,d".format(ui.hashes))
            Line("Found / accepted", "${ui.found} / ${ui.accepted}")
        }

        heat?.let { h ->
            Panel("Phone health", 3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Battery temperature", color = Mute)
                    Text("%.1f°C".format(h.tempC), fontFamily = Mono, fontWeight = FontWeight.Bold, color = heatColour(h))
                }
                h.thermalLabel?.let { Line("Phone heat (Android)", it) }
                Line("Battery", "${h.level}%" + if (h.plugged) " · charging" else "")
                Text(
                    "Mining pauses at ${Heat.PAUSE_C.toInt()}°C until the battery is back under ${Heat.RESUME_C.toInt()}°C, and stops completely at ${Heat.STOP_C.toInt()}°C or if Android reports critical heat.",
                    color = Mute, fontSize = 12.sp,
                )
            }
        }

        Panel("Settings", 4) {
            Text("Threads: ${threads.toInt()} of $maxT (each uses 64 MB)", color = Mute)
            if (maxT > 1) Slider(threads, { threads = it }, valueRange = 1f..maxT.toFloat(), steps = (maxT - 2).coerceAtLeast(0), enabled = !ui.running,
                onValueChangeFinished = { Store.setThreads(ctx, threads.toInt()) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Only while charging")
                    Text(if (chargingOnly) "Mines only when plugged in" else "Off: mines on battery too, pausing below 25% battery", color = Mute, fontSize = 12.sp)
                }
                Switch(chargingOnly, { chargingOnly = it; Store.setChargingOnly(ctx, it) })
            }
        }

        val begin = {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            else MinerService.start(ctx)
        }
        Solid(
            onClick = {
                if (ui.running) MinerService.stop(ctx)
                else if (!Store.heatNoticeAccepted(ctx)) showHeatNotice = true
                else begin()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (ui.running) "STOP MINING" else "START MINING", fontWeight = FontWeight.Bold) }

        // One-time notice before the first mining session: what mining does to a phone, and whose risk it is.
        if (showHeatNotice) AlertDialog(
            onDismissRequest = { showHeatNotice = false },
            title = { Text("Before you mine", fontFamily = Display) },
            text = {
                Text(
                    "Mining keeps your phone's processor busy the whole time it runs. The phone gets warm, the battery drains faster, " +
                        "and steady heat wears a battery out sooner.\n\n" +
                        "Soqzilla watches the battery temperature: it pauses at ${Heat.PAUSE_C.toInt()}°C and stops at ${Heat.STOP_C.toInt()}°C. " +
                        "That lowers the risk; it doesn't remove it. Mine somewhere cool, take thick cases off, and never under a pillow, in bed or in the sun.\n\n" +
                        "Soqzilla is free, experimental software with no warranty. You mine at your own risk."
                )
            },
            confirmButton = { TextButton({ Store.acceptHeatNotice(ctx); showHeatNotice = false; begin() }) { Text("I UNDERSTAND, START") } },
            dismissButton = { TextButton({ showHeatNotice = false }) { Text("NOT NOW") } },
        )

        if (ui.submissions.isNotEmpty()) Panel("Found", 5) {
            for (s in ui.submissions) {
                if (s.txid != null) Line("Block ref ${s.refHeight}", "+${sqz(s.reward)} SQZ")
                else Line("Block ref ${s.refHeight}", s.error ?: "failed")
            }
            Text("Found coins count once their transaction is in a SOQ block (about a minute).", color = Mute, fontSize = 12.sp)
        }
        Text("Address ${short(me.address)}", color = Mute, fontSize = 12.sp)
        ContactLine()
    }
}


@Composable
private fun heatColour(h: Heat.Reading) = when {
    h.tooHot || h.mustStop -> Hot
    h.warm -> Warn
    else -> Fg
}

/** Banner at the top of the Mine screen: safety stop, too hot, or getting warm. Nothing when all is fine. */
@Composable
private fun HeatWarning(h: Heat.Reading?, stoppedFor: String?) {
    val (colour, text) = when {
        stoppedFor != null -> Hot to "Mining stopped for safety: the $stoppedFor. Let the phone cool down before starting again."
        h == null -> return
        h.mustStop || h.tooHot -> Hot to "Too hot: battery at ${"%.0f".format(h.tempC)}°C. Mining is paused until it is back under ${Heat.RESUME_C.toInt()}°C and stops completely at ${Heat.STOP_C.toInt()}°C."
        h.warm -> Warn to "Getting warm: battery at ${"%.0f".format(h.tempC)}°C. Mining pauses at ${Heat.PAUSE_C.toInt()}°C. Taking the case off or moving somewhere cooler helps."
        else -> return
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colour.copy(alpha = 0.15f))) {
        Text(text, Modifier.padding(14.dp), color = colour, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NetworkInfo(s: JSONObject?) {
    if (s == null) { Text("Connecting…", color = Mute); return }
    val net = s.optString("net")
    if (net == "test") Text("TEST NETWORK (tag SQX): coins here are worthless test coins", color = Warn, fontWeight = FontWeight.Bold)
    if (!s.optBoolean("deployed")) { Text("Not launched yet. The launch block will be announced."); Line("SOQ block", s.optString("indexedHeight")); return }
    val launch = s.optJSONObject("genesis")?.optInt("launchHeight") ?: 0
    val height = s.optInt("indexedHeight")
    if (!s.optBoolean("launched")) { Text("Launches at SOQ block $launch, about ${duration((launch - height) * 60.0)} from now."); return }
    val minted = s.optString("minted").toDoubleOrNull() ?: 0.0
    LinearProgressIndicator(progress = { (minted / Rules.CAP).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(14.dp).border(2.dp, Rule).padding(2.dp), color = Signal, trackColor = Paper, gapSize = 0.dp, drawStopIndicator = {})
    Line("Mined", "${sqz(s.optString("minted"))} of 21B")
    Line("Reward per find", "${sqz(s.optString("reward"))} SQZ")
    Line("Finds so far", sqz(s.optString("mints")))
    Line("Holders", sqz(s.optString("holders")))
    val expected = s.optString("expectedHashesPerMint").toDoubleOrNull()
    if (expected != null) Line("Network speed (est.)", "%,.0f H/s".format(expected / 60))
    Line("SOQ block", height.toString())
    FeeJar(s)
}

/** The relay's SOQ, which pays every find's network fee; anyone can top it up. */
@Composable
private fun FeeJar(s: JSONObject) {
    val relay = s.optJSONObject("relay") ?: return
    val shors = relay.optString("balance").toLongOrNull() ?: return
    val days = shors / (FIND_FEE_SHORS * 1440.0)
    Line("Fee jar", "%,.1f SOQ · ${if (days >= 2) "%.0f days".format(days) else "%.1f days".format(days)}".format(shors / 1e8))
}

// ---------- Wallet ----------

/** A send the user has filled in, checked locally, waiting for confirmation. */
private class SendPlan(val to: String, val program: ByteArray, val amount: Long, val firstTime: Boolean, val lookalike: String?)

@Composable
fun WalletScreen(me: Store.Me, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val info = polled(20_000, me.address to refresh) { Store.api(ctx).address(me.address) }
    val coins = polled(30_000, me.address to refresh) { Store.api(ctx).utxos(me.address) }
    val status = polled(60_000) { Store.api(ctx).status() }
    val unlocked by Session.unlocked.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<SendPlan?>(null) }
    var setPass by remember { mutableStateOf(false) }
    val hasPass = remember(refresh, unlocked, setPass) { Store.hasPassphrase(ctx) }
    val soqBalance = coins?.sumOf { it.value } ?: 0L

    Screen(modifier) {
        Title("Wallet", eyebrow = hexOf("WALLET") + "  ·  your SQZ")
        Panel("Your address", 1) {
            Text(me.address, fontFamily = Mono, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ghost({ copy(ctx, me.address); note = if (Store.clipboardWipe(ctx)) "Address copied. The clipboard clears itself in 60 s." else "Address copied" }) { Text("COPY") }
                Ghost({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, me.address), "Share address"))
                }) { Text("SHARE") }
            }
        }

        Panel("Balance", 2) {
            Text("${sqz(info?.optString("balance"))} SQZ", fontSize = 28.sp, fontFamily = Display, color = Fg)
            val conf = info?.optString("confirmedBalance")
            if (conf != null && conf != info?.optString("balance")) Text("${sqz(conf)} SQZ settled (12+ blocks), the rest is recent", color = Mute, fontSize = 12.sp)
            Line("SOQ for fees", soq(soqBalance))
            if (Store.walletReplaced(ctx)) Text("Your previous wallet could not be opened on this phone, so a new one was made. If it held SQZ, restore it from its backup code in Settings.", color = Hot, fontSize = 13.sp)
            if (!hasPass) Text("No passphrase yet: anyone holding this phone unlocked could open the wallet. Set one below.", color = Warn, fontSize = 13.sp)
            else if (!Store.backedUp(ctx)) Text("You haven't saved your backup code yet (Settings → Backup). Lose this phone and the coins go with it.", color = Warn, fontSize = 13.sp)
        }

        Panel("Send SQZ", 3) {
            when {
                !hasPass -> {
                    Text("Set a passphrase first. It protects sending and your backup code. Mining keeps working without it, even while the wallet is locked.", color = Mute, fontSize = 13.sp)
                    Solid({ setPass = true }) { Text("SET A PASSPHRASE") }
                }
                !unlocked -> UnlockPanel()
                else -> {
                    OutlinedTextField(to, { to = it.trim() }, label = { Text("To (sq1p…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Amount (whole SQZ)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    val lowOnFees = soqBalance < 2 * FIND_FEE_SHORS + Tx.DUST
                    Text(
                        "Finding coins is free: the relay pays. Sending is a transaction from your own wallet, so the Soqucoin network charges a tiny fee in SOQ (about 0.017 SOQ a send). " +
                            if (lowOnFees) "You have no SOQ yet: if you've mined at least once, tap Get fee SOQ for a one-off 0.05 SOQ, enough for about 3 sends." else "You have SOQ for about ${soqBalance / FIND_FEE_SHORS} sends.",
                        color = Mute, fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Solid(enabled = !busy, onClick = {
                            note = null
                            runCatching { planSend(ctx, me, to, amount, info) }.fold({ plan = it }, { note = "Not sent: ${it.message}" })
                        }) { Text("SEND") }
                        if (lowOnFees) Ghost(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                note = runCatching { withContext(Dispatchers.IO) { Store.api(ctx).gas(me.address) } }
                                    .fold({ "${soq(it.optString("amount").toLongOrNull() ?: 0L)} SOQ for fees is on its way. It shows here in a minute or so." }, { "No fee SOQ: ${it.message}" })
                                busy = false
                                refresh++
                            }
                        }) { Text("GET FEE SOQ") }
                        Ghost({ Session.lock() }) { Text("LOCK") }
                    }
                }
            }
        }

        note?.let { Text(it, color = Signal) }

        val history = info?.optJSONArray("history")
        if (history != null && history.length() > 0) Panel("History", 4) {
            val tip = info?.optInt("tipHeight") ?: 0
            for (i in 0 until history.length()) {
                val e = history.getJSONObject(i)
                val conf = tip - e.optInt("height") + 1
                val mine = e.optString("from") == me.programHex
                val label = when (e.optString("type")) {
                    "mint" -> "Mined"
                    else -> if (mine) "Sent to ${short(Bech32m.encode("sq", e.optString("to").unhex()))}" else "Received"
                }
                val sign = if (e.optString("type") == "transfer" && mine) "-" else "+"
                Line("$label · ${if (conf < 6) "$conf conf" else "done"}", "$sign${sqz(e.optString("amount"))}")
            }
        }
        ContactLine()
    }

    plan?.let { pl ->
        SendConfirmDialog(pl, busy, onCancel = { plan = null }) {
            scope.launch {
                busy = true
                note = runCatching { withContext(Dispatchers.IO) { broadcastPlan(ctx, me, pl, status) } }
                    .fold({ Store.addRecipient(ctx, pl.to); to = ""; amount = ""; "Sent: ${short(it)}. It counts once it is in a block." }, { "Not sent: ${it.message}" })
                busy = false
                plan = null
                refresh++
            }
        }
    }
    if (setPass) PassphraseDialog(change = false, onClose = { setPass = false; refresh++ })
}

/** The passphrase field and, when turned on, fingerprint unlock. */
@Composable
private fun UnlockPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("The wallet is locked. Mining carries on regardless.", color = Mute, fontSize = 13.sp)
    OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    err?.let { Text(it, color = Hot, fontSize = 13.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Solid(enabled = !busy && pass.isNotEmpty(), onClick = {
            scope.launch {
                busy = true
                err = null
                val r = runCatching { withContext(Dispatchers.Default) { Store.unlock(ctx, pass.toCharArray()) } }
                r.fold({ u -> if (u != null) { Session.open(u.keys); pass = "" } else err = "Wrong passphrase" }, { err = it.message })
                busy = false
            }
        }) { Text(if (busy) "CHECKING…" else "UNLOCK") }
        if (Bio.enabled(ctx)) Ghost(enabled = !busy, onClick = {
            val act = ctx as? FragmentActivity ?: return@Ghost
            Bio.unlock(act) { key, e ->
                if (key != null) runCatching { Store.unlockWithKey(ctx, key) }.getOrNull()?.let { Session.open(it) } ?: run { err = "Fingerprint unlock failed. Use the passphrase." }
                else if (e != null) err = e
            }
        }) { Text("FINGERPRINT") }
    }
}

/** Set a first passphrase, or change it. */
@Composable
private fun PassphraseDialog(change: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ok = new1.length >= Vault.MIN_LENGTH && new1 == new2 && (!change || old.isNotEmpty())
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (change) "Change the passphrase" else "Set a passphrase", fontFamily = Display) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("At least ${Vault.MIN_LENGTH} characters. A few unrelated words are easier to remember than symbols. It can't be recovered: if you forget it, restore the wallet from its backup code.", color = Mute, fontSize = 13.sp)
                if (change) PassField(old, "Current passphrase") { old = it }
                PassField(new1, "New passphrase") { new1 = it }
                PassField(new2, "Repeat it") { new2 = it }
                if (new1.isNotEmpty() && new1.length < Vault.MIN_LENGTH) Text("${Vault.MIN_LENGTH - new1.length} more characters", color = Mute, fontSize = 12.sp)
                if (new2.isNotEmpty() && new1 != new2) Text("The two don't match yet", color = Mute, fontSize = 12.sp)
                err?.let { Text(it, color = Hot, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !busy, onClick = {
                scope.launch {
                    busy = true
                    err = null
                    err = runCatching {
                        withContext(Dispatchers.Default) {
                            val keys = if (change) (Store.unlock(ctx, old.toCharArray()) ?: error("the current passphrase is wrong")).keys
                            else Store.unprotectedKeys(ctx) ?: error("this wallet already has a passphrase")
                            Store.setPassphrase(ctx, keys.seed.copyOf(), new1.toCharArray())
                            keys
                        }
                    }.fold({ Session.open(it); null }, { it.message })
                    busy = false
                    if (err == null) onClose()
                }
            }) { Text(if (busy) "SAVING…" else "SAVE") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("CANCEL") } },
    )
}

@Composable
private fun PassField(value: String, label: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())

/** Check everything that can be checked without the network, and flag first-time or look-alike recipients. */
private fun planSend(ctx: Context, me: Store.Me, to: String, amountText: String, info: JSONObject?): SendPlan {
    val program = Bech32m.decode("sq", to) ?: error("that is not an sq1p address (check every character)")
    require(program.hex() != me.programHex) { "that is your own address" }
    val amount = amountText.toLongOrNull() ?: error("enter an amount")
    require(amount > 0) { "enter an amount" }
    val balance = info?.optString("balance")?.toLongOrNull() ?: error("balance not loaded yet")
    require(amount <= balance) { "you have ${"%,d".format(balance)} SQZ" }
    val known = Store.recipients(ctx)
    val lookalike = (known + me.address).firstOrNull { it != to && looksLike(it, to) }
    return SendPlan(to, program, amount, to !in known, lookalike)
}

/** Address poisoning: same opening and closing characters, different middle. */
private fun looksLike(a: String, b: String): Boolean {
    val x = a.removePrefix("sq1p"); val y = b.removePrefix("sq1p")
    val head = x.zip(y).takeWhile { it.first == it.second }.size
    val tail = x.reversed().zip(y.reversed()).takeWhile { it.first == it.second }.size
    return (head >= 4 && tail >= 4) || head >= 7 || tail >= 7
}

@Composable
private fun SendConfirmDialog(p: SendPlan, busy: Boolean, onCancel: () -> Unit, onSend: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    var tail by remember { mutableStateOf("") }
    val tailOk = p.lookalike == null || tail.trim().equals(p.to.takeLast(6), ignoreCase = true)
    val ok = (!p.firstTime || checked) && tailOk
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Send ${"%,d".format(p.amount)} SQZ?", fontFamily = Display) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("To", color = Mute, fontSize = 12.sp)
                Text(p.to.chunked(4).joinToString(" "), fontFamily = Mono, fontSize = 13.sp)
                Text("Network fee about 0.017 SOQ. A send can't be undone.", color = Mute, fontSize = 12.sp)
                if (p.lookalike != null) {
                    Text("Careful: this looks like an address you know, but it is a different one:", color = Hot, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(p.lookalike.chunked(4).joinToString(" "), fontFamily = Mono, fontSize = 12.sp, color = Mute)
                    Text("Scammers make look-alike addresses. Type the last 6 characters of the address you are sending to, to continue.", color = Mute, fontSize = 12.sp)
                    OutlinedTextField(tail, { tail = it }, label = { Text("Last 6 characters") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (p.firstTime) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { checked = it })
                    Text("You haven't sent to this address before. I've checked it with the person I'm paying.", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(enabled = ok && !busy, onClick = onSend) { Text(if (busy) "SENDING…" else "SEND") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("CANCEL") } },
    )
}

private fun copy(ctx: Context, text: String, sensitive: Boolean = false) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Soqzilla", text)
    // Android 13+: keeps the text out of the clipboard preview and from keyboards' suggestions
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
    // Clear it after 60 s if it still holds what we put there (or if the phone won't let us look)
    if (sensitive || Store.clipboardWipe(ctx)) Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            val now = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (now == null || now == text) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }, 60_000)
}

/** Blocks screenshots and screen recording while it is on screen (the backup code). */
@Composable
private fun SecureWhileShown() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!Look.blockCapture.value) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Build, sign and broadcast a confirmed send with the unlocked key. Returns the txid. */
private fun broadcastPlan(ctx: Context, me: Store.Me, p: SendPlan, status: JSONObject?): String {
    val keys = Session.keys() ?: error("the wallet locked; unlock it and try again")
    check(keys.program.hex() == me.programHex) { "the unlocked key is not this wallet's" }
    val magic = Rules.magicOf(status?.optString("magic") ?: error("server not reachable"))
    val api = Store.api(ctx)
    val coin = api.utxos(me.address).maxByOrNull { it.value } ?: error("you need a little SOQ for the fee: tap Get fee SOQ")
    require(coin.value > 2000 * FEE_RATE + Tx.DUST) { "not enough SOQ for the fee: tap Get fee SOQ" }
    val payload = Rules.transferPayload(magic, p.amount, p.program)
    val tx = Tx.build(keys, listOf(coin), listOf(TxOut(0, Rules.opReturn(payload))), FEE_RATE)
    return api.broadcast(tx.rawHex)
}

// ---------- Top ----------

@Composable
fun TopScreen(me: Store.Me, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val top = polled(30_000) { Store.api(ctx).top() }
    Screen(modifier) {
        Title("Top", eyebrow = hexOf("TOP") + "  ·  leaderboard")
        val list = top?.optJSONArray("top")
        if (list == null) Text("Loading…", color = Mute)
        else if (list.length() == 0) Text("Nobody has mined yet.", color = Mute)
        else Panel {
            for (i in 0 until list.length()) {
                val r = list.getJSONObject(i)
                val mine = r.optString("address") == me.address
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.optInt("rank")}", Modifier.width(32.dp), color = Mute, fontFamily = Mono)
                    Text(if (mine) "You" else short(r.optString("address")), Modifier.weight(1f), color = if (mine) Signal else MaterialTheme.colorScheme.onSurface, fontFamily = Mono, fontSize = 13.sp)
                    Text(sqz(r.optString("balance")), fontFamily = Mono, fontSize = 13.sp)
                }
                if (i < list.length() - 1) HorizontalDivider(color = Mute.copy(alpha = 0.35f))
            }
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(me: Store.Me, onWalletChanged: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val unlocked by Session.unlocked.collectAsState()
    val running = MinerState.ui.collectAsState().value.running
    var tick by remember { mutableIntStateOf(0) }
    val hasPass = remember(tick, unlocked) { Store.hasPassphrase(ctx) }
    val bioOn = remember(tick) { Bio.enabled(ctx) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var autoLock by remember { mutableFloatStateOf(Store.autoLockMinutes(ctx).toFloat()) }
    var lockOnLeave by remember { mutableStateOf(Store.lockOnLeave(ctx)) }
    var wipe by remember { mutableStateOf(Store.clipboardWipe(ctx)) }
    val block by Look.blockCapture.collectAsState()
    val theme by Look.theme.collectAsState()
    var shownSeed by remember { mutableStateOf<String?>(null) }

    Screen(modifier) {
        Title("Settings", eyebrow = hexOf("SETTINGS"))

        Panel("Security", 1) {
            Line("Passphrase", if (hasPass) "set" else "not set")
            Line("Wallet", if (unlocked) "unlocked" else "locked")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ghost({ dialog = if (hasPass) "change" else "set" }) { Text(if (hasPass) "CHANGE PASSPHRASE" else "SET A PASSPHRASE") }
                if (unlocked) Ghost({ Session.lock() }) { Text("LOCK NOW") }
            }
            if (Bio.available(ctx)) {
                Text(
                    if (bioOn) "Fingerprint unlock is on. The passphrase always works too."
                    else "Unlock with your fingerprint instead of typing the passphrase. The phone keeps a locked copy of the wallet key that only a strong fingerprint match opens; the passphrase is never stored.",
                    color = Mute, fontSize = 12.sp,
                )
                if (bioOn) Ghost({ Bio.disable(ctx); tick++ }) { Text("TURN FINGERPRINT OFF") }
                else Ghost(enabled = hasPass, onClick = { dialog = "bio" }) { Text("TURN FINGERPRINT ON") }
            }
            Text("Lock after ${autoLock.toInt()} min without a tap", color = Mute, fontSize = 13.sp)
            Slider(autoLock, { autoLock = it }, valueRange = 1f..30f, onValueChangeFinished = { Store.setAutoLockMinutes(ctx, autoLock.toInt()) })
            SettingSwitch("Lock 60 s after leaving the app", "Also when the screen goes off", lockOnLeave) { lockOnLeave = it; Store.setLockOnLeave(ctx, it) }
            SettingSwitch("Clear the clipboard 60 s after copying", "The backup code is always cleared", wipe) { wipe = it; Store.setClipboardWipe(ctx, it) }
            SettingSwitch("Block screenshots", "Of the whole app. The backup code is always blocked.", block) { Store.setBlockCapture(ctx, it); Look.blockCapture.value = it }
        }

        Panel("Backup", 2) {
            Text("Your backup code is the wallet. Anyone who has it can spend your SQZ. Write it on paper and keep it somewhere safe.", color = Mute, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ghost({ if (hasPass) dialog = "show" else Store.unprotectedKeys(ctx)?.let { shownSeed = it.seed.hex() } }) { Text("SHOW BACKUP CODE") }
                Ghost(enabled = !running, onClick = { dialog = "restore" }) { Text("RESTORE") }
            }
            if (running) Text("Stop mining to restore a different wallet.", color = Mute, fontSize = 12.sp)
        }

        Panel("Appearance", 3) {
            for ((value, label) in listOf("system" to "Follow the phone", "light" to "Light", "dark" to "Dark")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(theme == value, { Store.setTheme(ctx, value); Look.theme.value = value })
                    Text(label)
                }
            }
        }

        if (BuildConfig.SERVER_SETTING) Panel("Server (test build)", 4) {
            Text(Store.apiUrl(ctx), fontFamily = Mono, fontSize = 12.sp)
            Ghost({ dialog = "server" }) { Text("CHANGE") }
        }

        Panel("About", 5) {
            Line("Version", BuildConfig.VERSION_NAME)
            Text("Soqzilla is free, experimental, open-source software provided as is, without warranty. Mining heats phones and wears batteries: you mine at your own risk. Not an investment. Not affiliated with Soqucoin or Soqucoin Labs.", color = Mute, fontSize = 12.sp)
            ContactLine()
        }

        Panel("Danger", 6) {
            Text("Delete this wallet from the phone. Without its backup code the SQZ on it is gone for good.", color = Mute, fontSize = 13.sp)
            Ghost(enabled = !running, onClick = { dialog = "delete" }) { Text("DELETE WALLET FROM THIS PHONE", color = Hot) }
            if (running) Text("Stop mining first.", color = Mute, fontSize = 12.sp)
        }
        note?.let { Text(it, color = Signal) }
    }

    when (dialog) {
        "set" -> PassphraseDialog(change = false, onClose = { dialog = null; tick++ })
        "change" -> PassphraseDialog(change = true, onClose = { dialog = null; tick++ })
        "show" -> AskPassDialog("Show the backup code", onClose = { dialog = null }) { u -> shownSeed = u.keys.seed.hex(); dialog = null }
        "bio" -> AskPassDialog("Turn fingerprint unlock on", onClose = { dialog = null }) { u ->
            dialog = null
            val act = ctx as? FragmentActivity
            if (act == null) note = "Fingerprint unlock is not available here."
            else Bio.enable(act, u.vaultKey) { err -> note = err ?: "Fingerprint unlock is on."; tick++ }
        }
        "restore" -> RestoreDialog(hasPass, onClose = { dialog = null }) { Session.lock(); onWalletChanged(); note = "Wallet restored."; tick++ }
        "delete" -> DeleteDialog(hasPass, onClose = { dialog = null }) { Session.lock(); onWalletChanged(); note = "Wallet deleted. A new, empty one was made."; tick++ }
        "server" -> ServerDialog(onClose = { dialog = null })
    }

    shownSeed?.let { seed ->
        SecureWhileShown()
        AlertDialog(
            onDismissRequest = { shownSeed = null },
            title = { Text("Backup code", fontFamily = Display) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(seed.chunked(4).joinToString(" "), fontFamily = Mono)
                    Text("Write it down exactly. Never type it into a website or send it to anyone, including the people behind Soqzilla.", color = Mute, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton({ Store.setBackedUp(ctx); shownSeed = null }) { Text("I'VE WRITTEN IT DOWN") } },
            dismissButton = { TextButton({ copy(ctx, seed, sensitive = true); note = "Backup code copied. It clears from the clipboard in 60 s." }) { Text("COPY") } },
        )
    }
}

@Composable
private fun SettingSwitch(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = Mute, fontSize = 12.sp)
        }
        Switch(on, set)
    }
}

/** Ask for the passphrase, then hand over the opened wallet. */
@Composable
private fun AskPassDialog(title: String, onClose: () -> Unit, onOpen: (Store.Unlocked) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(title, fontFamily = Display) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PassField(pass, "Passphrase") { pass = it }
                err?.let { Text(it, color = Hot, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = pass.isNotEmpty() && !busy, onClick = {
                scope.launch {
                    busy = true
                    val r = runCatching { withContext(Dispatchers.Default) { Store.unlock(ctx, pass.toCharArray()) } }
                    busy = false
                    r.fold({ u -> if (u != null) { Session.open(u.keys); onOpen(u) } else err = "Wrong passphrase" }, { err = it.message })
                }
            }) { Text(if (busy) "CHECKING…" else "OK") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("CANCEL") } },
    )
}

@Composable
private fun RestoreDialog(hasPass: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val clean = code.filter { !it.isWhitespace() }.lowercase()
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Restore a wallet", fontFamily = Display) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This replaces the wallet on this phone. Save its backup code first if it holds anything." +
                    if (hasPass) " The restored wallet keeps your current passphrase." else "", color = Mute, fontSize = 13.sp)
                OutlinedTextField(code, { code = it }, label = { Text("64-character backup code") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (hasPass) PassField(pass, "Current passphrase") { pass = it }
                err?.let { Text(it, color = Hot, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = clean.isHex(64) && (!hasPass || pass.isNotEmpty()) && !busy, onClick = {
                scope.launch {
                    busy = true
                    err = runCatching {
                        withContext(Dispatchers.Default) {
                            val p = if (hasPass) pass.toCharArray().also { Store.unlock(ctx, it) ?: error("the current passphrase is wrong") } else null
                            Store.restore(ctx, clean.unhex(), p)
                        }
                    }.fold({ null }, { it.message })
                    busy = false
                    if (err == null) { onClose(); onDone() }
                }
            }) { Text(if (busy) "RESTORING…" else "REPLACE") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("CANCEL") } },
    )
}

@Composable
private fun DeleteDialog(hasPass: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Delete the wallet?", fontFamily = Display, color = Hot) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This removes the wallet from this phone. Only its backup code can bring it back. Type DELETE to confirm.", color = Mute, fontSize = 13.sp)
                OutlinedTextField(typed, { typed = it }, label = { Text("Type DELETE") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (hasPass) PassField(pass, "Passphrase") { pass = it }
                err?.let { Text(it, color = Hot, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = typed.trim() == "DELETE" && (!hasPass || pass.isNotEmpty()) && !busy, onClick = {
                scope.launch {
                    busy = true
                    err = runCatching {
                        withContext(Dispatchers.Default) {
                            if (hasPass) Store.unlock(ctx, pass.toCharArray()) ?: error("wrong passphrase")
                            Store.deleteWallet(ctx)
                            Store.me(ctx) // a fresh wallet, so mining has an address
                        }
                    }.fold({ null }, { it.message })
                    busy = false
                    if (err == null) { onClose(); onDone() }
                }
            }) { Text(if (busy) "DELETING…" else "DELETE", color = Hot) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("CANCEL") } },
    )
}

@Composable
private fun ServerDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Store.apiUrl(ctx)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Indexer server") },
        text = {
            Column {
                Text("Anyone can run the open-source indexer. Leave empty for the default.", color = Mute, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ Store.setApiUrl(ctx, url.takeIf { it.isNotBlank() }); onClose() }) { Text("Save") } },
        dismissButton = { TextButton({ Store.setApiUrl(ctx, null); onClose() }) { Text("Default") } },
    )
}
