package dev.ahmed.spotter.engine.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class TokenBucket(initialRateBps: Long = 0) {
    private val rateBps = AtomicLong(initialRateBps)
    private val mutex = Mutex()
    private var tokens = 0.0
    private var lastNs = System.nanoTime()

    fun setRate(bytesPerSecond: Long) {
        rateBps.set(bytesPerSecond.coerceAtLeast(0))
    }

    fun rate(): Long = rateBps.get()

    suspend fun take(n: Int) {
        if (n <= 0) return
        while (true) {
            val rate = rateBps.get()
            if (rate <= 0L) return
            val waitMs = mutex.withLock {
                refill(rate)
                if (tokens >= n) {
                    tokens -= n
                    0L
                } else {
                    val missing = n - tokens
                    ((missing / rate.toDouble()) * 1000.0).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    private fun refill(rate: Long) {
        val now = System.nanoTime()
        val elapsedSec = (now - lastNs) / 1_000_000_000.0
        tokens = (tokens + elapsedSec * rate).coerceAtMost(rate.toDouble())
        lastNs = now
    }
}
