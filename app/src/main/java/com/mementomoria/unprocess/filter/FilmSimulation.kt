package com.mementomoria.unprocess.filter

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
 * The user-selectable film simulations. Their declaration order is also the
 * order of the camera-screen rotary dial. [NORMAL] is the identity (no processing).
 *
 * The three film looks aim at the characteristic colour signature of their
 * real-world emulsions:
 *
 * - GOLD   — Kodak Gold 200: warm golden/amber cast, soft pleasing skin tones,
 *            lifted shadows with a hint of warmth, slightly muted greens and
 *            blues, gentle contrast.
 * - SUPER  — Fujifilm Superia X-Tra 400: slightly cool, signature green-cyan
 *            cast in the shadows, vibrant greens and cyan-shifted blues, more
 *            contrast and grain than Gold.
 * - NECTAR — Kodak Ektar 100: fine grain, high saturation but smooth (no
 *            crushed shadows or clipped colour blobs), neutral-to-slightly-cool
 *            tonality, vivid yet smooth blues, magenta-shifted reds.
 *
 * Values are intentionally moderate compared to a literal LR-recipe transcript:
 * combining strong contrast with strong HSL saturation tends to flatten busy
 * regions into solid-colour patches ("Flächen verschluckt"), so each filter
 * leans on *vibrance* (which respects already-saturated pixels) over flat
 * saturation, and keeps Light/HSL pushes in a range that doesn't clip when
 * stacked with the master tone curve and calibration.
 */
enum class FilmSimulation(val displayName: String, val params: FilmParams) {
    NORMAL(
        displayName = "Normal",
        params = FilmParams(),
    ),

    // -------- Kodak Gold 200 --------
    //
    // Signature: warm amber, slightly green-yellow midtones, lifted shadows,
    // soft highlights, restrained greens & blues, fine-medium grain.
    GOLD(
        displayName = "Film Gold",
        params = FilmParams(
            contrast = 6,
            highlights = -10,
            shadows = 14,
            whites = -4,
            blacks = 8,
            temp = 14, tint = 3,
            vibrance = 10, saturation = 0,
            // Lifted black point, gentle highlight roll-off — the typical
            // "film matte" toe-and-shoulder.
            pointCurve = listOf(0 to 14, 64 to 66, 192 to 196, 255 to 246),
            // Slightly warmer red channel, distinctly warmer blue channel
            // (blue shadows lifted toward cyan-yellow, blue highlights pulled
            // down → overall amber cast).
            redCurve = listOf(0 to 6, 255 to 252),
            blueCurve = listOf(0 to 14, 128 to 122, 255 to 226),
            hslHue = FilmParams.hsl(
                FilmParams.HSL_ORANGE to 4,    // skin slightly warmer
                FilmParams.HSL_YELLOW to 8,    // yellow → greenish-yellow
                FilmParams.HSL_GREEN to 6,
            ),
            hslSat = FilmParams.hsl(
                FilmParams.HSL_RED to 6,
                FilmParams.HSL_ORANGE to 10,
                FilmParams.HSL_YELLOW to 10,
                FilmParams.HSL_GREEN to -6,    // muted greens
                FilmParams.HSL_BLUE to -10,    // muted blues
                FilmParams.HSL_AQUA to -6,
            ),
            hslLum = FilmParams.hsl(
                FilmParams.HSL_YELLOW to 6,
                FilmParams.HSL_BLUE to -4,
            ),
            shadowTint = 4,                    // warm shadow tint (magenta-ish)
            redPrimarySat = 4,
            greenPrimaryHue = -6,
            grainAmount = 18, grainSize = 25, grainRoughness = 50,
            vignetteAmount = -6, vignetteMidpoint = 55,
        ),
    ),

    // -------- Fujifilm Superia X-Tra 400 --------
    //
    // Signature: cool/neutral overall, green-cyan shadow cast, vibrant greens,
    // cyan-leaning blues, slightly muted skin warmth, visible grain.
    SUPER(
        displayName = "Film Super",
        params = FilmParams(
            contrast = 9,
            highlights = -10,
            shadows = 12,
            whites = -4,
            blacks = 10,
            temp = -5, tint = -6,              // cool + slight green
            vibrance = 12, saturation = 3,
            // Mild S-curve, slight black-point lift (less than Gold —
            // Superia has more snap in the shadows).
            pointCurve = listOf(0 to 8, 64 to 60, 192 to 202, 255 to 250),
            // Green channel lifted in shadows → trademark Superia "green cast"
            // in dark mid-tones. Blue lifted in shadows, slightly pulled at
            // top → cyan-leaning highlights.
            greenCurve = listOf(0 to 12, 255 to 248),
            blueCurve = listOf(0 to 10, 128 to 130, 255 to 244),
            hslHue = FilmParams.hsl(
                FilmParams.HSL_RED to 3,
                FilmParams.HSL_YELLOW to -4,   // yellow → greenish
                FilmParams.HSL_GREEN to -8,    // green → cyan
                FilmParams.HSL_AQUA to -4,
                FilmParams.HSL_BLUE to 4,      // blue → cyan
            ),
            hslSat = FilmParams.hsl(
                FilmParams.HSL_RED to 6,
                FilmParams.HSL_ORANGE to 4,
                FilmParams.HSL_YELLOW to 4,
                FilmParams.HSL_GREEN to 12,    // vivid greens — Superia hallmark
                FilmParams.HSL_AQUA to 10,
                FilmParams.HSL_BLUE to 6,
                FilmParams.HSL_MAGENTA to 4,
            ),
            hslLum = FilmParams.hsl(
                FilmParams.HSL_GREEN to 6,
                FilmParams.HSL_BLUE to -8,
            ),
            shadowTint = -8,                   // green shadow tint
            redPrimarySat = 4,
            greenPrimaryHue = 6, greenPrimarySat = 10,
            bluePrimarySat = 5,
            grainAmount = 28, grainSize = 28, grainRoughness = 55,
            vignetteAmount = -4, vignetteMidpoint = 55,
        ),
    ),

    // -------- Kodak Ektar 100 --------
    //
    // Signature: fine grain, vivid but smooth colour, neutral-to-slightly-cool
    // tonality, deep but not crushed blues, magenta-shifted reds, gentle
    // contrast (key to keeping saturated areas from flattening).
    //
    // The old recipe used contrast +18 plus an aggressive S-curve plus +15
    // HSL sat for several channels plus calibration on top — that stacked
    // into flat-colour blobs in saturated regions ("Flächen verschluckt").
    // This rebuild keeps the look but cuts the extremes roughly in half and
    // leans on vibrance (which preserves already-saturated pixels) instead
    // of flat HSL saturation pumping.
    NECTAR(
        displayName = "Film Nectar",
        params = FilmParams(
            contrast = 8,                      // was 18 — main cause of crushing
            highlights = -8,
            shadows = 8,
            whites = 0,
            blacks = 4,                        // small lift, no negative crush
            temp = -3, tint = 3,               // slightly cool, faint magenta
            vibrance = 18, saturation = 4,     // vibrance does the heavy lifting
            // Gentle S, lifted-but-not-flat blacks, no highlight clipping.
            pointCurve = listOf(0 to 4, 64 to 60, 192 to 198, 255 to 252),
            // Channel curves nearly neutral — Ektar's character is in the
            // HSL/primary domain, not in per-channel curve shaping.
            redCurve = listOf(0 to 3, 255 to 253),
            blueCurve = listOf(0 to 5, 255 to 248),
            hslHue = FilmParams.hsl(
                FilmParams.HSL_RED to 3,       // red → slight magenta
                FilmParams.HSL_ORANGE to 2,
                FilmParams.HSL_GREEN to -6,    // green → cyan-leaning
                FilmParams.HSL_AQUA to -4,
                FilmParams.HSL_BLUE to -4,     // blue → toward cyan (Ektar blues)
            ),
            hslSat = FilmParams.hsl(           // moderate, was +15 across the board
                FilmParams.HSL_RED to 10,
                FilmParams.HSL_ORANGE to 8,
                FilmParams.HSL_YELLOW to 6,
                FilmParams.HSL_GREEN to 8,
                FilmParams.HSL_AQUA to 10,
                FilmParams.HSL_BLUE to 10,
                FilmParams.HSL_MAGENTA to 6,
            ),
            hslLum = FilmParams.hsl(
                FilmParams.HSL_RED to -2,
                FilmParams.HSL_ORANGE to 2,
                FilmParams.HSL_GREEN to 2,
                FilmParams.HSL_BLUE to -4,     // was -18 — main cause of dark blue blobs
            ),
            // Mild calibration touch — without this Ektar loses some of its
            // characteristic primary shift, but +6/+5/+8 keeps it from
            // doubling up with HSL into clipping.
            redPrimaryHue = 4, redPrimarySat = 6,
            greenPrimaryHue = -4, greenPrimarySat = 4,
            bluePrimaryHue = 6, bluePrimarySat = 8,
            grainAmount = 5, grainSize = 18, grainRoughness = 40,
        ),
    ),

    // -------- Dyna (Pro DSLR HDR-Look) --------
    //
    // Goal: the restrained, natural-looking HDR mode you'd get out of a
    // modern pro DSLR/mirrorless body (Sony α7, Canon R5, Nikon Z9 ADL/HDR).
    // Reveals shadow/highlight detail and adds a touch of local contrast —
    // and that's it. No colour shifts, no saturation pumping, no HSL
    // remapping. The eye reads it as "the same scene, just with more
    // dynamic range visible".
    //
    // The previous Dyna was tuned like an over-cooked Instagram HDR
    // (shadows +80, HSL Blue +18, clarity 55 at a strong scaling). That
    // crushed the tonal range into a narrow band, then asked Clarity to
    // re-expand it — which produces edge halos and flat colour blocks
    // ("Flächen verschwinden"). This version stays in a tonal range where
    // Clarity can do its job without artefacts.
    DYNA(
        displayName = "Film Dyna",
        params = FilmParams(
            // No global contrast change. Pro HDR modes don't push contrast —
            // they actually reduce it slightly to fit more range in.
            contrast = 0,
            // Moderate, not aggressive. Enough to bring detail back from
            // clipped highlights and crushed shadows without flattening
            // the midtones into the kind of compressed band that makes
            // Clarity halo.
            highlights = -35,
            shadows = 38,
            whites = -5,
            blacks = 5,
            // NO white-balance shift. "Nichts wo sich die Farbe krass ändert."
            temp = 0, tint = 0,
            // Vibrance for the colour pop, a touch of flat saturation on
            // top. Still well below anything that would push hues into
            // flat colour blocks at the HSL stage.
            vibrance = 14, saturation = 8,
            // Almost-linear master curve with the tiniest toe lift and
            // shoulder pull, so blacks/whites have a hair of headroom for
            // Clarity to work in without clipping.
            pointCurve = listOf(0 to 4, 255 to 251),
            // No HSL hue, saturation, or luminance adjustments — colour
            // rendition stays exactly as captured.
            // Clarity is the defining trait. 45 with the strength 0.45
            // scaling + edge-attenuation in FilmFilter still stays clear
            // of haloing on high-contrast edges.
            clarity = 45,
        ),
    );

    fun next(): FilmSimulation {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}
