package com.soqzilla.app.mining

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Submission(val at: Long, val refHeight: Int, val txid: String?, val error: String?, val reward: String?)

data class MinerUi(
    val running: Boolean = false,
    val status: String = "Stopped",
    val hashrate: Double = 0.0,
    val hashes: Long = 0,
    val found: Int = 0,
    val accepted: Int = 0,
    val refHeight: Int? = null,
    val expectedHashes: Double? = null,
    val reward: String? = null,
    val submissions: List<Submission> = emptyList(),
    /** Set when mining was stopped automatically (heat); cleared on the next start. */
    val stoppedFor: String? = null,
    /** Set when a mining thread ran out of memory and ended. */
    val memoryNote: String? = null,
    /** Battery temperature as the miner last read it. */
    val tempC: Double? = null,
)

/** What the miner service is doing, for the UI. */
object MinerState {
    val ui = MutableStateFlow(MinerUi())
    fun update(f: (MinerUi) -> MinerUi) = ui.update(f)
}
