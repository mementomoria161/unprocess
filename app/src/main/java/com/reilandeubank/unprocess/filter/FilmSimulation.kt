package com.reilandeubank.unprocess.filter

/**
 * Lightroom-style adjustment parameters for a single film simulation.
 *
 * Curves are control-point lists in (input, output) space, both 0..255.
 * Light/Color/HSL/Calibration values are in Lightroom's familiar -100..+100
 * scale and are interpreted by [FilmFilter].
 *
 * HSL arrays are indexed by [HSL_RED]..[HSL_MAGENTA] (8 color ranges centred
 * on 0°, 30°, 60°, 120°, 180°, 240°, 270°, 300°).
 */
data class FilmParams(
    // Light
    val contrast: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val whites: Int = 0,
    val blacks: Int = 0,
    // Color
    val temp: Int = 0,
    val tint: Int = 0,
    val vibrance: Int = 0,
    val saturation: Int = 0,
    // Tone curves — control points in (input, output) on 0..255
    val pointCurve: List<Pair<Int, Int>> = LINEAR,
    val redCurve: List<Pair<Int, Int>> = LINEAR,
    val greenCurve: List<Pair<Int, Int>> = LINEAR,
    val blueCurve: List<Pair<Int, Int>> = LINEAR,
    // HSL — 8 colors (red, orange, yellow, green, aqua, blue, purple, magenta)
    val hslHue: IntArray = IntArray(8),
    val hslSat: IntArray = IntArray(8),
    val hslLum: IntArray = IntArray(8),
    // Calibration (simplified — applied as extra hue/sat shifts on primaries)
    val shadowTint: Int = 0,
    val redPrimaryHue: Int = 0,
    val redPrimarySat: Int = 0,
    val greenPrimaryHue: Int = 0,
    val greenPrimarySat: Int = 0,
    val bluePrimaryHue: Int = 0,
    val bluePrimarySat: Int = 0,
    // Split toning / Color grading
    val highlightHue: Int = 0,
    val highlightSat: Int = 0,
    val shadowHue: Int = 0,
    val shadowSat: Int = 0,
    // Detail
    val clarity: Int = 0,
    val structure: Int = 0,
    // Effects
    val grainAmount: Int = 0,
    val grainSize: Int = 0,
    val grainRoughness: Int = 0,
    val vignetteAmount: Int = 0,
    val vignetteMidpoint: Int = 50,
) {
    companion object {
        val LINEAR: List<Pair<Int, Int>> = listOf(0 to 0, 255 to 255)

        const val HSL_RED = 0
        const val HSL_ORANGE = 1
        const val HSL_YELLOW = 2
        const val HSL_GREEN = 3
        const val HSL_AQUA = 4
        const val HSL_BLUE = 5
        const val HSL_PURPLE = 6
        const val HSL_MAGENTA = 7

        /** Convenience builder for HSL arrays where most entries are zero. */
        fun hsl(vararg pairs: Pair<Int, Int>): IntArray {
            val arr = IntArray(8)
            for ((idx, v) in pairs) arr[idx] = v
            return arr
        }
    }
}

/**
 * The user-selectable film simulations. Cycled in this declaration order via
 * the camera-screen filter button. [NORMAL] is the identity (no processing).
 */
enum class FilmSimulation(val displayName: String, val params: FilmParams) {
    NORMAL(
        displayName = "Normal",
        params = FilmParams(),
    ),
    GOLD(
        displayName = "Gold",
        params = FilmParams(
            contrast = 12, highlights = -18, shadows = 22, whites = -8, blacks = 15,
            temp = 9, tint = 4, vibrance = 12, saturation = 4,
            pointCurve = listOf(0 to 12, 64 to 60, 192 to 200, 255 to 248),
            redCurve = listOf(0 to 8, 255 to 250),
            blueCurve = listOf(0 to 18, 255 to 235),
            hslHue = FilmParams.hsl(
                FilmParams.HSL_RED to 6,
                FilmParams.HSL_ORANGE to 4,
                FilmParams.HSL_YELLOW to 12,
                FilmParams.HSL_GREEN to 10,
            ),
            hslSat = FilmParams.hsl(
                FilmParams.HSL_RED to 8,
                FilmParams.HSL_ORANGE to 12,
                FilmParams.HSL_YELLOW to 15,
                FilmParams.HSL_GREEN to -8,
                FilmParams.HSL_BLUE to -15,
            ),
            hslLum = FilmParams.hsl(
                FilmParams.HSL_YELLOW to 10,
                FilmParams.HSL_BLUE to -12,
            ),
            shadowTint = 5,
            redPrimaryHue = 8, redPrimarySat = 6,
            greenPrimaryHue = -10, greenPrimarySat = 5,
            bluePrimaryHue = 12, bluePrimarySat = 10,
            grainAmount = 22, grainSize = 25, grainRoughness = 50,
            vignetteAmount = -10, vignetteMidpoint = 50,
        ),
    ),
    SUPER(
        displayName = "Super",
        params = FilmParams(
            contrast = 10, highlights = -12, shadows = 15, whites = -5, blacks = 12,
            temp = -3, tint = -5, vibrance = 10, saturation = 5,
            pointCurve = listOf(0 to 10, 96 to 90, 160 to 170, 255 to 255),
            greenCurve = listOf(0 to 12, 255 to 248),
            blueCurve = listOf(0 to 8, 128 to 120, 255 to 245),
            hslHue = FilmParams.hsl(
                FilmParams.HSL_RED to 4,
                FilmParams.HSL_GREEN to -12,
                FilmParams.HSL_BLUE to 6,
            ),
            hslSat = FilmParams.hsl(
                FilmParams.HSL_RED to 12,
                FilmParams.HSL_ORANGE to 8,
                FilmParams.HSL_YELLOW to 6,
                FilmParams.HSL_GREEN to 10,
                FilmParams.HSL_BLUE to -10,
                FilmParams.HSL_MAGENTA to 8,
            ),
            hslLum = FilmParams.hsl(
                FilmParams.HSL_GREEN to 8,
                FilmParams.HSL_BLUE to -15,
            ),
            shadowTint = -8,
            redPrimaryHue = 0, redPrimarySat = 8,
            greenPrimaryHue = 8, greenPrimarySat = 12,
            bluePrimarySat = 6,
            grainAmount = 32, grainSize = 30, grainRoughness = 55,
        ),
    ),
    NECTAR(
        displayName = "Nectar",
        params = FilmParams(
            contrast = 18, highlights = -15, shadows = 12, whites = 8, blacks = -5,
            temp = -2, tint = 0, vibrance = 15, saturation = 10,
            pointCurve = listOf(0 to 0, 64 to 52, 192 to 205, 255 to 255),
            hslHue = FilmParams.hsl(
                FilmParams.HSL_ORANGE to -4,
                FilmParams.HSL_BLUE to -8,
            ),
            hslSat = FilmParams.hsl(
                FilmParams.HSL_RED to 15,
                FilmParams.HSL_ORANGE to 6,
                FilmParams.HSL_YELLOW to 8,
                FilmParams.HSL_GREEN to 10,
                FilmParams.HSL_BLUE to 15,
                FilmParams.HSL_MAGENTA to 10,
            ),
            hslLum = FilmParams.hsl(
                FilmParams.HSL_RED to -8,
                FilmParams.HSL_ORANGE to 6,
                FilmParams.HSL_BLUE to -18,
            ),
            redPrimaryHue = 6, redPrimarySat = 12,
            greenPrimaryHue = -8, greenPrimarySat = 10,
            bluePrimaryHue = 10, bluePrimarySat = 15,
            clarity = 10, structure = 8,
            grainAmount = 6, grainSize = 20, grainRoughness = 50,
        ),
    ),
    PLUS(
        displayName = "Plus",
        params = FilmParams(
            contrast = 6, highlights = -10, shadows = 15, whites = -6, blacks = 14,
            temp = 8, tint = 3, vibrance = 8, saturation = 2,
            pointCurve = listOf(0 to 13, 96 to 96, 160 to 168, 255 to 245),
            redCurve = listOf(0 to 10, 255 to 252),
            blueCurve = listOf(0 to 15, 255 to 240),
            hslHue = FilmParams.hsl(
                FilmParams.HSL_RED to 5,
                FilmParams.HSL_ORANGE to 3,
                FilmParams.HSL_GREEN to 6,
            ),
            hslSat = FilmParams.hsl(
                FilmParams.HSL_RED to 8,
                FilmParams.HSL_ORANGE to 10,
                FilmParams.HSL_YELLOW to 5,
                FilmParams.HSL_GREEN to -8,
                FilmParams.HSL_BLUE to -12,
                FilmParams.HSL_MAGENTA to 4,
            ),
            hslLum = FilmParams.hsl(
                FilmParams.HSL_RED to -5,
                FilmParams.HSL_BLUE to -10,
            ),
            shadowTint = 6,
            redPrimaryHue = 6, redPrimarySat = 5,
            bluePrimaryHue = 8, bluePrimarySat = 6,
            highlightHue = 20, highlightSat = 8,
            shadowHue = 30, shadowSat = 6,
            grainAmount = 26, grainSize = 28, grainRoughness = 50,
        ),
    );

    fun next(): FilmSimulation {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}
