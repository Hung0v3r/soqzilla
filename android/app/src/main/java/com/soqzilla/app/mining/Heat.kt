package com.soqzilla.app.mining

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Heat and battery limits for mining. Android does not let apps read the CPU temperature, so this uses the
 * battery temperature (what the phone itself protects) plus the system's own thermal status where available.
 */
object Heat {
    // Battery temperature (not the CPU's). Under steady load a phone's battery commonly sits around 38-42°C;
    // lithium-ion cells are usually rated for charging up to 45°C.
    const val WARN_C = 40.0   // show a warning
    const val PAUSE_C = 42.0  // pause mining...
    const val RESUME_C = 38.0 // ...until it cools to this
    const val STOP_C = 45.0   // stop mining altogether; the user has to start it again

    data class Reading(val tempC: Double, val plugged: Boolean, val level: Int, val thermal: Int) {
        /** Android's thermal status name, or null on phones older than Android 10. */
        val thermalLabel: String?
            get() = when (thermal) {
                -1 -> null
                PowerManager.THERMAL_STATUS_NONE -> "normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                else -> "emergency"
            }

        val mustStop: Boolean get() = tempC >= STOP_C || thermal >= PowerManager.THERMAL_STATUS_CRITICAL
        val tooHot: Boolean get() = tempC >= PAUSE_C || thermal >= PowerManager.THERMAL_STATUS_SEVERE
        val warm: Boolean get() = tempC >= WARN_C || thermal >= PowerManager.THERMAL_STATUS_MODERATE

        /** Reads after "The …": "battery reached 45°C" / "phone reported critical heat". */
        fun stopReason() = if (tempC >= STOP_C) "battery reached ${"%.0f".format(tempC)}°C" else "phone reported ${thermalLabel ?: "critical"} heat"
    }

    fun read(ctx: Context): Reading? {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val thermal = if (Build.VERSION.SDK_INT >= 29) (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else -1
        return Reading(
            tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
            plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / scale,
            thermal = thermal,
        )
    }
}
