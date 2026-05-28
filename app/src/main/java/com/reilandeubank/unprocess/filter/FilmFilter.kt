package com.reilandeubank.unprocess.filter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * In-place(-ish) film simulation processor. [apply] returns the same Bitmap
 * passed in (after mutating its pixels) — the caller does not need to recycle
 * a separate output.
 *
 * The pipeline mirrors the order a Lightroom-style preset would apply:
 *
 *   WB (temp/tint) → light/contrast tone LUT (incl. master point curve)
 *   → per-channel R/G/B curves → HSL (hue/sat/lum per color range)
 *   → calibration shifts on primaries → vibrance/saturation → split-toning
 *   → grain → vignette
 *
 * Bitmaps are processed in row strips so we never need a full second
 * IntArray copy of a 12+ MP capture in memory.
 */
object FilmFilter {

    /** Number of rows processed per chunk — caps peak temp buffer at ~width * 64 * 4 B. */
    private const val ROW_CHUNK = 64

    /** Center hue (degrees) for each of the 8 HSL color ranges. */
    private val HSL_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f)

    /**
     * Applies [simulation] to [bitmap]. Returns either the same bitmap
     * (mutated in place — possible when the input is already mutable) or a
     * new mutable copy (if the input is immutable, or for NORMAL when nothing
     * needs to be done it just returns the input unchanged).
     *
     * Callers must compare the returned reference against the input and
     * recycle the unused one — see usage in `CameraFragment.handleCaptureClick`.
     */
    fun apply(bitmap: Bitmap, simulation: FilmSimulation): Bitmap {
        if (simulation == FilmSimulation.NORMAL) return bitmap
        val p = simulation.params
        val target = if (bitmap.isMutable) bitmap
            else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        return processInPlace(target, p)
    }

    private fun processInPlace(bitmap: Bitmap, p: FilmParams): Bitmap {

        val masterLut = buildMasterLut(p)
        val redLut = buildChannelLut(p.redCurve)
        val greenLut = buildChannelLut(p.greenCurve)
        val blueLut = buildChannelLut(p.blueCurve)

        val tempShift = p.temp / 100f
        val tintShift = p.tint / 100f
        val vibrance = p.vibrance / 100f
        val saturation = p.saturation / 100f

        val width = bitmap.width
        val height = bitmap.height

        // Grain is deterministic per-bitmap so the saved JPEG matches the
        // thumbnail; seed depends on dimensions only.
        val grain = if (p.grainAmount != 0) {
            Grain(p.grainAmount, p.grainSize, p.grainRoughness, width, height)
        } else null

        val vignette = if (p.vignetteAmount != 0) {
            Vignette(p.vignetteAmount, p.vignetteMidpoint, width, height)
        } else null

        val rowBuf = IntArray(width * ROW_CHUNK)

        var y = 0
        while (y < height) {
            val rows = min(ROW_CHUNK, height - y)
            bitmap.getPixels(rowBuf, 0, width, 0, y, width, rows)

            for (i in 0 until rows * width) {
                val px = rowBuf[i]
                val a = px ushr 24 and 0xff
                var r = px ushr 16 and 0xff
                var g = px ushr 8 and 0xff
                var b = px and 0xff

                // --- White balance (simple scaled multipliers around 1.0) ---
                if (tempShift != 0f || tintShift != 0f) {
                    var rf = r / 255f
                    var gf = g / 255f
                    var bf = b / 255f
                    if (tempShift != 0f) {
                        rf *= 1f + 0.25f * tempShift
                        bf *= 1f - 0.25f * tempShift
                    }
                    if (tintShift != 0f) {
                        gf *= 1f - 0.20f * tintShift
                        rf *= 1f + 0.05f * tintShift
                        bf *= 1f + 0.05f * tintShift
                    }
                    r = (rf * 255f).toInt().coerceIn(0, 255)
                    g = (gf * 255f).toInt().coerceIn(0, 255)
                    b = (bf * 255f).toInt().coerceIn(0, 255)
                }

                // --- Master tone LUT (light adjustments + point curve) ---
                r = masterLut[r]
                g = masterLut[g]
                b = masterLut[b]

                // --- Per-channel curves ---
                r = redLut[r]
                g = greenLut[g]
                b = blueLut[b]

                // --- HSL adjustments + calibration shifts on primaries ---
                val hsv = floatArrayOf(0f, 0f, 0f)
                Color.RGBToHSV(r, g, b, hsv)

                applyHsl(hsv, p)
                applyCalibration(hsv, p)
                applyShadowTint(hsv, p.shadowTint)
                applySplitToning(hsv, p)

                // --- Vibrance / saturation ---
                if (vibrance != 0f) {
                    val s = hsv[1]
                    val boost = vibrance * (1f - s)
                    hsv[1] = (s + boost).coerceIn(0f, 1f)
                }
                if (saturation != 0f) {
                    hsv[1] = (hsv[1] * (1f + saturation)).coerceIn(0f, 1f)
                }

                val rgb = Color.HSVToColor(hsv)
                r = rgb ushr 16 and 0xff
                g = rgb ushr 8 and 0xff
                b = rgb and 0xff

                rowBuf[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }

            // Effects that need pixel coordinates (vignette, grain) — separate
            // pass keeps the per-pixel inner loop branch-free for the common
            // adjustments above.
            if (grain != null || vignette != null) {
                var idx = 0
                for (ry in 0 until rows) {
                    val absY = y + ry
                    for (x in 0 until width) {
                        val px = rowBuf[idx]
                        var r = px ushr 16 and 0xff
                        var g = px ushr 8 and 0xff
                        var b = px and 0xff
                        val a = px ushr 24 and 0xff

                        if (vignette != null) {
                            val mul = vignette.factorAt(x, absY)
                            r = (r * mul).toInt().coerceIn(0, 255)
                            g = (g * mul).toInt().coerceIn(0, 255)
                            b = (b * mul).toInt().coerceIn(0, 255)
                        }
                        if (grain != null) {
                            val n = grain.valueAt(x, absY)
                            r = (r + n).coerceIn(0, 255)
                            g = (g + n).coerceIn(0, 255)
                            b = (b + n).coerceIn(0, 255)
                        }

                        rowBuf[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        idx++
                    }
                }
            }

            bitmap.setPixels(rowBuf, 0, width, 0, y, width, rows)
            y += rows
        }

        return bitmap
    }

    // -------- LUT construction --------

    /**
     * Builds a 256-entry LUT that combines contrast, highlights, shadows,
     * whites, blacks and the master point curve into a single lookup.
     * Light values are interpreted as -100..+100 (Lightroom-ish).
     */
    private fun buildMasterLut(p: FilmParams): IntArray {
        val lut = IntArray(256)
        val contrast = p.contrast / 100f
        val highlights = p.highlights / 100f
        val shadows = p.shadows / 100f
        val whites = p.whites / 100f
        val blacks = p.blacks / 100f

        for (i in 0..255) {
            var v = i / 255f

            if (contrast != 0f) {
                v = ((v - 0.5f) * (1f + contrast) + 0.5f).coerceIn(0f, 1f)
            }
            if (highlights != 0f && v > 0.5f) {
                val w = (v - 0.5f) * 2f
                v = (v + highlights * w * 0.25f).coerceIn(0f, 1f)
            }
            if (shadows != 0f && v < 0.5f) {
                val w = (0.5f - v) * 2f
                v = (v + shadows * w * 0.25f).coerceIn(0f, 1f)
            }
            if (whites != 0f && v > 0.7f) {
                val w = ((v - 0.7f) / 0.3f).coerceIn(0f, 1f)
                v = (v + whites * w * 0.2f).coerceIn(0f, 1f)
            }
            if (blacks != 0f && v < 0.3f) {
                val w = ((0.3f - v) / 0.3f).coerceIn(0f, 1f)
                v = (v + blacks * w * 0.2f).coerceIn(0f, 1f)
            }

            // Apply master point curve in 0..255 space.
            val afterCurve = applyCurve(v * 255f, p.pointCurve)
            lut[i] = afterCurve.toInt().coerceIn(0, 255)
        }
        return lut
    }

    /** Builds a 256-entry LUT from a per-channel control-point curve. */
    private fun buildChannelLut(curve: List<Pair<Int, Int>>): IntArray {
        val lut = IntArray(256)
        for (i in 0..255) {
            lut[i] = applyCurve(i.toFloat(), curve).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /**
     * Linear-interpolates [x] (0..255) through the sorted-by-input control
     * points in [curve]. Returns 0..255.
     */
    private fun applyCurve(x: Float, curve: List<Pair<Int, Int>>): Float {
        if (curve.isEmpty()) return x
        val sorted = if (curve.size <= 1 || curve.zipWithNext().all { it.first.first <= it.second.first }) {
            curve
        } else {
            curve.sortedBy { it.first }
        }
        if (x <= sorted.first().first) return sorted.first().second.toFloat()
        if (x >= sorted.last().first) return sorted.last().second.toFloat()
        for (i in 0 until sorted.size - 1) {
            val (x0, y0) = sorted[i]
            val (x1, y1) = sorted[i + 1]
            if (x in x0.toFloat()..x1.toFloat()) {
                val t = if (x1 == x0) 0f else (x - x0) / (x1 - x0).toFloat()
                return y0 + t * (y1 - y0)
            }
        }
        return x
    }

    // -------- HSL --------

    /**
     * Computes weights (sum-normalized) of [hueDeg] across the 8 HSL color
     * ranges. Result is written into [out] (length 8).
     */
    private fun hueWeights(hueDeg: Float, out: FloatArray) {
        var sum = 0f
        for (i in 0..7) {
            val center = HSL_CENTERS[i]
            var d = abs(hueDeg - center)
            if (d > 180f) d = 360f - d
            // Falloff window of 30° on either side — gives smooth blend
            // between adjacent Lightroom color ranges.
            val w = if (d >= 60f) 0f else {
                val n = 1f - d / 60f
                n * n
            }
            out[i] = w
            sum += w
        }
        if (sum > 0f) {
            for (i in 0..7) out[i] /= sum
        }
    }

    private val tmpWeights = FloatArray(8)

    private fun applyHsl(hsv: FloatArray, p: FilmParams) {
        // Cheap fast-path: skip the trig+weighting if all adjustments are 0.
        var anyAdj = false
        for (i in 0..7) {
            if (p.hslHue[i] != 0 || p.hslSat[i] != 0 || p.hslLum[i] != 0) { anyAdj = true; break }
        }
        if (!anyAdj) return

        hueWeights(hsv[0], tmpWeights)
        var hueShift = 0f
        var satFactor = 1f
        var lumFactor = 1f
        for (i in 0..7) {
            val w = tmpWeights[i]
            if (w == 0f) continue
            hueShift += w * p.hslHue[i] * 0.36f  // ±100 → ±36°
            satFactor += w * p.hslSat[i] / 100f
            lumFactor += w * p.hslLum[i] / 100f
        }
        hsv[0] = (hsv[0] + hueShift).mod(360f)
        hsv[1] = (hsv[1] * satFactor).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * lumFactor).coerceIn(0f, 1f)
    }

    private fun applyCalibration(hsv: FloatArray, p: FilmParams) {
        if (p.redPrimaryHue == 0 && p.redPrimarySat == 0 &&
            p.greenPrimaryHue == 0 && p.greenPrimarySat == 0 &&
            p.bluePrimaryHue == 0 && p.bluePrimarySat == 0) return

        val h = hsv[0]
        // Distance to each primary; weight = max(0, 1 - d/60)^2
        val wR = primaryWeight(h, 0f)
        val wG = primaryWeight(h, 120f)
        val wB = primaryWeight(h, 240f)
        val sum = wR + wG + wB
        if (sum <= 0f) return
        val nR = wR / sum; val nG = wG / sum; val nB = wB / sum

        val hueShift = nR * p.redPrimaryHue * 0.4f +
                nG * p.greenPrimaryHue * 0.4f +
                nB * p.bluePrimaryHue * 0.4f
        val satFactor = 1f + (nR * p.redPrimarySat + nG * p.greenPrimarySat + nB * p.bluePrimarySat) / 100f

        hsv[0] = (hsv[0] + hueShift).mod(360f)
        hsv[1] = (hsv[1] * satFactor).coerceIn(0f, 1f)
    }

    private fun primaryWeight(hue: Float, center: Float): Float {
        var d = abs(hue - center)
        if (d > 180f) d = 360f - d
        if (d >= 60f) return 0f
        val n = 1f - d / 60f
        return n * n
    }

    private fun applyShadowTint(hsv: FloatArray, shadowTint: Int) {
        if (shadowTint == 0) return
        // Push hue toward magenta (+) or green (-) for low-value pixels only.
        val v = hsv[2]
        if (v >= 0.5f) return
        val w = (0.5f - v) * 2f
        val shift = if (shadowTint > 0) {
            // toward magenta (300°)
            (300f - hsv[0]).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it } * w * (shadowTint / 100f) * 0.25f
        } else {
            // toward green (120°)
            (120f - hsv[0]).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it } * w * (-shadowTint / 100f) * 0.25f
        }
        hsv[0] = (hsv[0] + shift).mod(360f)
    }

    private fun applySplitToning(hsv: FloatArray, p: FilmParams) {
        if (p.highlightSat == 0 && p.shadowSat == 0) return
        val v = hsv[2]
        // Linear weights: 0 at v=0.5 (mid), 1 at extremes.
        val hiW = ((v - 0.5f) * 2f).coerceAtLeast(0f)
        val shW = ((0.5f - v) * 2f).coerceAtLeast(0f)

        if (p.highlightSat != 0 && hiW > 0f) {
            val targetH = p.highlightHue.toFloat().mod(360f)
            val targetS = (p.highlightSat / 100f) * hiW
            blendTowardHs(hsv, targetH, targetS)
        }
        if (p.shadowSat != 0 && shW > 0f) {
            val targetH = p.shadowHue.toFloat().mod(360f)
            val targetS = (p.shadowSat / 100f) * shW
            blendTowardHs(hsv, targetH, targetS)
        }
    }

    private fun blendTowardHs(hsv: FloatArray, hue: Float, weight: Float) {
        // Soft additive: nudge hue toward target, raise saturation slightly.
        val current = hsv[0]
        var diff = hue - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        hsv[0] = (current + diff * weight * 0.3f).mod(360f)
        hsv[1] = (hsv[1] + weight * 0.3f).coerceIn(0f, 1f)
    }

    // -------- Effects --------

    /**
     * Deterministic noise field. Cell size set by [size] (Lightroom's grain
     * size 0..100 ≈ 1..5 px); cells smoothed by [roughness] using a cosine
     * blend with neighbours so grain doesn't look pixelated.
     */
    private class Grain(amount: Int, size: Int, roughness: Int, val w: Int, val h: Int) {
        private val amplitude: Float = amount * 0.45f
        private val cellPx: Int = max(1, (1 + size / 25f).toInt())
        private val smoothness: Float = (roughness / 100f).coerceIn(0f, 1f)
        private val rng = Random(w.toLong() * 73856093L xor h.toLong() * 19349663L xor amount.toLong())
        private val cellsX = max(1, (w + cellPx - 1) / cellPx)
        private val cellsY = max(1, (h + cellPx - 1) / cellPx)
        private val field = FloatArray(cellsX * cellsY).apply {
            for (i in indices) this[i] = (rng.nextFloat() - 0.5f) * 2f
        }

        fun valueAt(x: Int, y: Int): Int {
            val cx = min(cellsX - 1, x / cellPx)
            val cy = min(cellsY - 1, y / cellPx)
            val baseIdx = cy * cellsX + cx
            val base = field[baseIdx]
            // Light smoothing: blend with right/down neighbours by smoothness.
            val rightIdx = if (cx + 1 < cellsX) baseIdx + 1 else baseIdx
            val downIdx = if (cy + 1 < cellsY) baseIdx + cellsX else baseIdx
            val tx = (x % cellPx) / cellPx.toFloat()
            val ty = (y % cellPx) / cellPx.toFloat()
            val sx = if (smoothness > 0f) 0.5f - 0.5f * cos((tx * 3.1415927f)) else tx
            val sy = if (smoothness > 0f) 0.5f - 0.5f * cos((ty * 3.1415927f)) else ty
            val v = base * (1f - sx) * (1f - sy) +
                    field[rightIdx] * sx * (1f - sy) +
                    field[downIdx] * (1f - sx) * sy +
                    field[if (cx + 1 < cellsX && cy + 1 < cellsY) baseIdx + cellsX + 1 else baseIdx] * sx * sy
            return (v * amplitude).toInt()
        }
    }

    /**
     * Radial darkening (or brightening for positive [amount] — Lightroom-style
     * with negative amounts darkening corners). Midpoint controls how soon
     * the falloff begins from the centre.
     */
    private class Vignette(amount: Int, midpoint: Int, val w: Int, val h: Int) {
        private val cx = w / 2f
        private val cy = h / 2f
        private val maxR = sqrt(cx * cx + cy * cy)
        private val amt = amount / 100f  // negative → darker corners
        private val mid = (midpoint.coerceIn(0, 100)) / 100f

        fun factorAt(x: Int, y: Int): Float {
            val dx = x - cx
            val dy = y - cy
            val r = sqrt(dx * dx + dy * dy) / maxR  // 0 center, 1 corner
            if (r <= mid) return 1f
            val t = ((r - mid) / (1f - mid)).coerceIn(0f, 1f)
            val falloff = 1f - exp(-3f * t * t)  // smooth ramp
            return (1f + amt * falloff).coerceIn(0f, 2f)
        }
    }

    // -------- Float helpers --------

    private fun Float.mod(m: Float): Float {
        var r = this % m
        if (r < 0f) r += m
        return r
    }
}
