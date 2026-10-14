package com.aifusion.scanner

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded, latest-value route for realtime scan observations.
 *
 * All observations belonging to an analyzed frame are packaged together so
 * the preview, scan-session persistence and status UI can share one coherent
 * frame snapshot. A single AtomicReference deliberately replaces stale
 * snapshots instead of allowing a queue to grow when a consumer is slow.
 *
 * Depth arrays are copied at the boundary and must be treated as read-only by
 * every consumer. This avoids sharing mutable inference buffers with UI code.
 */
data class RoutedDepthData(
    val values: FloatArray,
    val width: Int,
    val height: Int,
    val capturedAtElapsedMs: Long
)

data class RealtimeDataBlock(
    val sequence: Long,
    val createdAtNanos: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val tracking: TrackingSnapshot,
    val target: ObjectTrackResult?,
    val depth: RoutedDepthData?,
    val inferenceMs: Long,
    val processingMs: Long
) {
    fun ageMs(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Long =
        ((nowNanos - createdAtNanos).coerceAtLeast(0L) / 1_000_000L)
}

data class RealtimeRouterStats(
    val publishedBlocks: Long,
    val replacedSnapshots: Long,
    val latestAgeMs: Long,
    val latestSequence: Long
)

class RealtimeDataRouter {
    private val sequence = AtomicLong(0L)
    private val published = AtomicLong(0L)
    private val replaced = AtomicLong(0L)
    private val latest = AtomicReference<RealtimeDataBlock?>(null)

    fun publish(
        frameWidth: Int,
        frameHeight: Int,
        tracking: TrackingSnapshot,
        target: ObjectTrackResult?,
        depth: RoutedDepthData?,
        inferenceMs: Long,
        processingMs: Long
    ): RealtimeDataBlock {
        val safeDepth = depth?.let {
            it.copy(values = it.values.copyOf())
        }
        val block = RealtimeDataBlock(
            sequence = sequence.incrementAndGet(),
            createdAtNanos = SystemClock.elapsedRealtimeNanos(),
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            tracking = tracking,
            target = target,
            depth = safeDepth,
            inferenceMs = inferenceMs.coerceAtLeast(0L),
            processingMs = processingMs.coerceAtLeast(0L)
        )
        if (latest.getAndSet(block) != null) replaced.incrementAndGet()
        published.incrementAndGet()
        return block
    }

    fun latest(): RealtimeDataBlock? = latest.get()

    fun stats(): RealtimeRouterStats {
        val block = latest.get()
        return RealtimeRouterStats(
            publishedBlocks = published.get(),
            replacedSnapshots = replaced.get(),
            latestAgeMs = block?.ageMs() ?: 0L,
            latestSequence = block?.sequence ?: 0L
        )
    }

    fun clear() {
        latest.set(null)
    }
}
