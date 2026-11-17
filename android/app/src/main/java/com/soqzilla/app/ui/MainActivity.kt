package com.soqzilla.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.soqzilla.app.data.Session
import com.soqzilla.app.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** App-wide look settings the screens can change live. */
object Look {
    val theme = MutableStateFlow("system")
    val blockCapture = MutableStateFlow(false)
}

/** A FragmentActivity because Android's fingerprint prompt needs one. */
class MainActivity : FragmentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val lockAway = Runnable { Session.lock() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Look.theme.value = Store.theme(this)
        Look.blockCapture.value = Store.blockCapture(this)
        setContent {
            val theme by Look.theme.collectAsState()
            val block by Look.blockCapture.collectAsState()
            LaunchedEffect(block) {
                if (block) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            // Auto-lock after the chosen minutes without a tap
            LaunchedEffect(Unit) {
                while (true) {
                    delay(5_000)
                    val idle = SystemClock.elapsedRealtime() - Session.lastActivity
                    if (Session.unlocked.value && idle > Store.autoLockMinutes(this@MainActivity) * 60_000L) Session.lock()
                }
            }
            SoqzillaTheme(theme) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var me by remember { mutableStateOf(Store.me(this)) }
                val navColors = NavigationBarItemDefaults.colors(selectedIconColor = OnSignal, selectedTextColor = Fg, indicatorColor = Signal, unselectedIconColor = Mute, unselectedTextColor = Mute)
                Scaffold(
                    // any touch anywhere counts as activity for auto-lock (observed, never consumed)
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); Session.touch() } }
                    },
                    containerColor = Paper,
                    bottomBar = {
                        Column {
                            HorizontalDivider(thickness = 2.dp, color = Rule)
                            NavigationBar(containerColor = Paper) {
                                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.PlayArrow, null) }, label = { Text("MINE") }, colors = navColors)
                                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.AccountBox, null) }, label = { Text("WALLET") }, colors = navColors)
                                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Star, null) }, label = { Text("TOP") }, colors = navColors)
                                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("SETTINGS") }, colors = navColors)
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        0 -> MineScreen(me, m)
                        1 -> WalletScreen(me, m)
                        2 -> TopScreen(me, m)
                        else -> SettingsScreen(me, onWalletChanged = { me = Store.me(this@MainActivity) }, modifier = m)
                    }
                }
            }
        }
    }

    // Lock 60 s after the app leaves the screen (another app, home, screen off), if that setting is on.
    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(lockAway)
        if (Store.lockOnLeave(this)) handler.postDelayed(lockAway, 60_000)
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(lockAway)
    }
}
