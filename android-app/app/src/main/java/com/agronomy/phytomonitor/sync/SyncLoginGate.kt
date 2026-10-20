package com.agronomy.phytomonitor.sync

/** Serializes only publication/cancellation, never authentication HTTP or encryption. */
internal class SyncLoginGate {
    private var revision = 0L

    @Synchronized fun begin(): Long = ++revision

    @Synchronized fun isCurrent(attempt: Long): Boolean = attempt == revision

    @Synchronized fun <T> serialized(action: () -> T): T = action()

    @Synchronized fun commit(attempt: Long, active: () -> Boolean, publish: () -> Unit): Boolean {
        if (attempt != revision || !active()) return false
        try {
            publish()
            return true
        } finally {
            revision++
        }
    }

    @Synchronized fun invalidate(clear: () -> Unit) {
        revision++
        clear()
    }
}
