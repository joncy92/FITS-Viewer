package com.spartantech.fitsviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.max
import kotlin.math.min

class AutoStretchTest {

    /** Same mapping as the renderer, returned in [0,1]. */
    private fun apply(v: Float, s: ChannelStats): Double {
        if (v <= s.min) return 0.0
        if (v >= s.max) return 1.0
        val x = (v - s.min).toDouble() / (s.max - s.min)
        return mtf(s.midtones.toDouble(), x)
    }

    private fun stretch(data: FloatArray): Pair<ChannelStats, FloatArray> {
        val sorted = data.copyOf().also { it.sort() }
        val stats = autoStretchStats(sorted, sorted.first(), sorted.last())
        return stats to sorted
    }

    private fun median(sorted: FloatArray) = sorted[sorted.size / 2]

    /** Sky background + noise + a few percent of bright "star" pixels. */
    private fun sky(n: Int, bg: Double, noise: Double, peak: Double, seed: Long = 1): FloatArray {
        val r = Random(seed)
        return FloatArray(n) {
            var v = bg + r.nextGaussian() * noise
            if (r.nextDouble() < 0.02) v += r.nextDouble() * r.nextDouble() * (peak - bg)
            min(peak, max(0.0, v)).toFloat()
        }
    }

    private fun assertWellStretched(data: FloatArray, noise: Double) {
        val (stats, sorted) = stretch(data)
        val med = median(sorted)
        // Background lands on the target level.
        assertEquals(0.25, apply(med, stats), 0.03)
        // Highlights are never hard clipped: white is the top of the data range.
        assertTrue(stats.max >= sorted.last())
        // Signal well above the background is bright but still below white, and
        // brighter signal keeps getting brighter (no flat, blown-out plateau).
        val s5 = apply((med + 5 * noise).toFloat(), stats)
        val s20 = apply((med + 20 * noise).toFloat(), stats)
        val s100 = apply((med + 100 * noise).toFloat(), stats)
        assertTrue("5 sigma: $s5", s5 > 0.35 && s5 < 0.6)
        assertTrue("20 sigma: $s20", s20 > s5 && s20 < 0.85)
        assertTrue("100 sigma: $s100", s100 > s20 && s100 < 0.97)
        // Shadows: only a small tail below the background is clipped to black.
        val black = sorted.count { apply(it, stats) <= 0.0 }
        assertTrue("black: $black", black <= sorted.size / 50)
    }

    @Test fun rawSixteenBitSub() = assertWellStretched(sky(100_000, 1200.0, 40.0, 65535.0), 40.0)

    @Test fun faintTwelveBitSub() = assertWellStretched(sky(100_000, 210.0, 6.0, 4095.0), 6.0)

    @Test fun normalisedFloatStack() = assertWellStretched(sky(100_000, 0.004, 0.0003, 1.0), 0.0003)

    @Test fun floatWithHugeRange() = assertWellStretched(sky(100_000, 3.0e5, 2.0e3, 4.0e9), 2.0e3)

    @Test fun negativeBackground() {
        val data = sky(100_000, 500.0, 20.0, 30000.0)
        for (i in data.indices) data[i] -= 800f
        assertWellStretched(data, 20.0)
    }

    @Test fun blackRegistrationBordersAreIgnored() {
        val data = sky(100_000, 0.02, 0.002, 1.0)
        for (i in 0 until 60_000) data[i] = 0f // more than half the frame is empty border
        val sorted = data.copyOf().also { it.sort() }
        val stats = autoStretchStats(sorted, sorted.first(), sorted.last())
        val realMedian = sorted[60_000 + 20_000]
        assertEquals(0.25, apply(realMedian, stats), 0.03)
    }

    @Test fun quantisedDataWithZeroMad() {
        // Most pixels sit on the black level; the rest are only a few ADU above it.
        val r = Random(3)
        val data = FloatArray(50_000) {
            val p = r.nextDouble()
            when { p < 0.7 -> 10f; p < 0.9 -> 11f; p < 0.99 -> 12f; else -> (13 + r.nextInt(240)).toFloat() }
        }
        val (stats, sorted) = stretch(data)
        assertTrue(stats.midtones < 0.5f)
        assertEquals(0.0, apply(10f, stats), 0.0)   // black level stays black
        assertEquals(0.25, apply(11f, stats), 0.03) // faintest signal is clearly visible
        assertTrue(apply(12f, stats) > apply(11f, stats))
        assertTrue(apply(100f, stats) > apply(12f, stats))
        assertTrue(apply(100f, stats) < 1.0)
        assertEquals(1.0, apply(sorted.last(), stats), 0.0)
    }

    @Test fun brightImageIsNotDarkenedOrBlown() {
        // Flat-field like frame: bright, low contrast.
        val r = Random(5)
        val data = FloatArray(50_000) { (30000 + r.nextGaussian() * 1500).toFloat() }
        val (stats, sorted) = stretch(data)
        assertEquals(0.5f, stats.midtones, 1e-6f)
        val out = apply(median(sorted), stats)
        assertTrue(out > 0.2 && out < 0.8)
        assertTrue(sorted.count { apply(it, stats) >= 0.999 } <= 50)
    }

    @Test fun constantImageDoesNotCrash() {
        val stats = autoStretchStats(FloatArray(1000) { 5f }, 5f, 5f)
        assertTrue(stats.max > stats.min)
        assertEquals(0.5f, stats.midtones, 0f)
    }

    @Test fun channelsAreStretchedIndependently() {
        // Strong colour cast: each channel has a different background level.
        val r = sky(50_000, 3000.0, 60.0, 65535.0, 1)
        val g = sky(50_000, 1500.0, 40.0, 65535.0, 2)
        val b = sky(50_000, 700.0, 25.0, 65535.0, 3)
        val sorted = listOf(r, g, b).map { c -> c.copyOf().also { it.sort() } }
        val lo = sorted.minOf { it.first() }
        val hi = sorted.maxOf { it.last() }
        for (s in sorted) {
            val stats = autoStretchStats(s, lo, hi)
            assertEquals(0.25, apply(median(s), stats), 0.03) // neutral background
        }
    }
}
