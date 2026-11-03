package com.mymesag.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * 抗重放滑动窗口（项目书 6.4）：按 (senderDeviceId, seq) 拒绝重复与过期序号。
 * 接收端以 seq 判序，仅凭 ts 不足以判序（设备时钟可能不同步）。
 */
class SeqWindow(private val windowSize: Long = 1000) {

    private data class State(val seen: MutableSet<Long> = HashSet(), var maxSeq: Long = 0)

    private val states = ConcurrentHashMap<String, State>()

    @Synchronized
    fun checkAndMark(senderDeviceId: String, seq: Long): Boolean {
        val state = states.getOrPut(senderDeviceId) { State() }
        if (seq <= state.maxSeq - windowSize) return false // 过期
        if (!state.seen.add(seq)) return false // 重复（重放）
        if (seq > state.maxSeq) state.maxSeq = seq
        state.seen.removeAll { it < state.maxSeq - windowSize }
        return true
    }
}
