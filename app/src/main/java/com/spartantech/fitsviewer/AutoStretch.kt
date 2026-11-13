package com.spartantech.fitsviewer

import kotlin.math.abs
import kotlin.math.max

/**
 * Per-channel display function: values <= [min] are black, values >= [max] are white and
 * [midtones] is the midtones balance of the transfer function applied in between
 * (0.5 = linear).
 */
data class ChannelStats(val min: Float, val max: Float, val midtones: Float)

// Auto stretch parameters, same defaults as PixInsight's ScreenTransferFunction auto stretch.
internal const val STF_SHADOWS_CLIP = -2.8      // shadows clipping point, in sigma units from the median
internal const val STF_TARGET_BACKGROUND = 0.25 // where the background (median) ends up, in [0,1]
internal const val MAD_TO_SIGMA = 1.4826        // makes MAD consistent with a Gaussian sigma

/** Midtones transfer function with midtones balance [m], evaluated at [x] in [0,1]. */
internal fun mtf(m: Double, x: Double): Double = when {
    x <= 0.0 -> 0.0
    x >= 1.0 -> 1.0
    else -> ((m - 1.0) * x) / ((2.0 * m - 1.0) * x - m)
}

internal fun medianOfSorted(a: FloatArray, start: Int, end: Int): Double {
    val n = end - start
    if (n <= 0) return 0.0
    val mid = start + n / 2
    return if (n % 2 == 1) a[mid].toDouble() else (a[mid - 1].toDouble() + a[mid].toDouble()) * 0.5
}

/**
 * Auto stretch for one channel, computed like PixInsight's unlinked STF auto stretch:
 *
 *   shadows   = median + STF_SHADOWS_CLIP * 1.4826 * MAD
 *   midtones  = MTF(targetBackground, median - shadows)   (in normalised units)
 *   highlights are never clipped: white stays at the top of the data range.
 *
 * [sorted] holds the channel's samples in ascending order; [lo]..[hi] is the data range of
 * the whole image, used to normalise to [0,1].
 */
internal fun autoStretchStats(sorted: FloatArray, lo: Float, hi: Float): ChannelStats {
    if (sorted.isEmpty() || !(hi > lo)) {
        // Nothing to stretch (no data, or a perfectly flat image).
        return ChannelStats(lo, if (hi > lo) hi else lo + 1f, 0.5f)
    }

    // Ignore pure black / saturated pixels (registration borders, clipped stars...) for the
    // statistics, as long as enough real data remains.
    var start = 0
    var end = sorted.size
    while (start < end && sorted[start] <= lo) start++
    while (end > start && sorted[end - 1] >= hi) end--
    if (end - start < max(16, sorted.size / 100)) { start = 0; end = sorted.size }
    val n = end - start

    val median = medianOfSorted(sorted, start, end)

    val deviations = FloatArray(n) { abs(sorted[start + it] - median.toFloat()) }
    deviations.sort()
    var sigma = MAD_TO_SIGMA * medianOfSorted(deviations, 0, n)

    if (sigma <= 0.0) {
        // More than half of the pixels share one value (heavily quantised data):
        // fall back to the central 68% spread, then to the mean absolute deviation.
        val p16 = sorted[start + ((n - 1) * 0.1587).toInt()].toDouble()
        val p84 = sorted[start + ((n - 1) * 0.8413).toInt()].toDouble()
        sigma = (p84 - p16) * 0.5
    }
    if (sigma <= 0.0) {
        var sum = 0.0
        for (d in deviations) sum += d
        sigma = 1.2533 * sum / n
    }
    if (!(sigma > 0.0)) return ChannelStats(lo, hi, 0.5f)

    val shadows = max(lo.toDouble(), median + STF_SHADOWS_CLIP * sigma)
    val span = hi.toDouble() - shadows
    if (!(span > 0.0)) return ChannelStats(lo, hi, 0.5f)

    // Median position after shadows clipping, normalised to [0,1].
    val x = (median - shadows) / span
    var midtones = if (x > 0.0 && x < 1.0) mtf(STF_TARGET_BACKGROUND, x) else 0.5
    // Only ever brighten: an image whose background is already brighter than the target
    // (flats, daylight, lunar / solar frames) just gets its shadows clipped.
    midtones = midtones.coerceIn(1e-9, 0.5)

    return ChannelStats(shadows.toFloat(), hi, midtones.toFloat())
}
