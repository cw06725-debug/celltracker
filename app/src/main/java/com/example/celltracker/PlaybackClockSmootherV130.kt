package com.example.celltracker

import kotlin.math.abs

/** UI-only position estimator for MediaPlayer/VideoView's sometimes coarse currentPosition.
 *  Uses monotonic uptime between real samples; never changes the recording time map or
 *  marks precise frame PTS. Reset after seek and on pause, do not extrapolate while paused.
 */
internal class PlaybackClockSmootherV130 {
    private var lastUi = 0L
    private var lastUptime = -1L
    private var running = false

    fun reset(positionMs: Long, uptimeMs: Long): Long {
        lastUi = positionMs.coerceAtLeast(0L)
        lastUptime = uptimeMs
        running = false
        return lastUi
    }

    fun tick(measuredMs: Long, uptimeMs: Long, playing: Boolean, durationMs: Long): Long {
        val actual = measuredMs.coerceIn(0L, durationMs)
        if (!playing) return reset(actual, uptimeMs)
        if (lastUptime < 0L || !running) {
            lastUi = actual
            lastUptime = uptimeMs
            running = true
            return actual
        }
        val dt = (uptimeMs-lastUptime).coerceIn(0L, 100L)
        val predicted = (lastUi + dt).coerceAtMost(durationMs)
        // A large mismatch indicates buffering, a seek, or a decoder position correction.
        // Small coarse updates are smoothed so the visible milliseconds don't jump in blocks.
        val gap = actual-predicted
        lastUi = when {
            abs(gap) > 350L -> actual
            gap > 30L -> (predicted + minOf(12L, gap/4)).coerceAtMost(durationMs)
            gap < -180L -> (predicted - 8L).coerceAtLeast(0L)
            else -> predicted
        }
        lastUptime = uptimeMs
        return lastUi
    }
}
