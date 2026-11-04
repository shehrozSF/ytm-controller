package com.shehrozsf.ytmcontroller

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val TAG = "PlaybarColors"

/**
 * Colours for the now-playing bar, derived from the album art. Like the system media
 * player: a tinted background, white text and controls, and one vivid accent for progress.
 */
data class PlaybarColors(
    val background: Color,
    val onBackground: Color,
    val accent: Color,
)

/** The system player always draws text and controls in white over its art tint. */
private val PLAYER_CONTENT = Color.White

/**
 * A port of One UI's `MediaShaderColorExtractor` (Samsung SystemUI): Palette with 24 colours
 * picks primary / secondary / accent swatches, then each is clamped into a narrow HSL band.
 * The clamping is why similar covers collapse onto near-identical colours. The system
 * player's shader gets the same three colours; we use primary as the background. The
 * accent is the seek bar's colour, which comes from a separate path: see [waveThemeColor].
 */
internal object PlaybarColorExtractor {
    private val cache = LruCache<String, PlaybarColors>(64)

    // Samsung's fallbacks when a swatch can't be produced at all.
    private const val FALLBACK_PRIMARY = 0xFF2196F3.toInt()
    private const val FALLBACK_SECONDARY = 0xFF03DAC6.toInt()
    private const val FALLBACK_ACCENT = 0xFF1976D2.toInt()

    private data class Extracted(val primary: Int, val secondary: Int, val accent: Int)

    fun cached(key: String): PlaybarColors? = cache.get(key)

    /** Runs on [Dispatchers.Default]; returns null if the art can't be read. */
    suspend fun extract(key: String, art: Bitmap): PlaybarColors? = withContext(Dispatchers.Default) {
        runCatching {
            if (art.isRecycled) return@withContext null
            // Hardware bitmaps can't be read pixel by pixel. Palette downsizes on its own
            // (same default area SystemUI gets), so no pre-scaling here.
            val readable = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
            val c = transform(base(centerSquare(readable)))
            // The seek bar is separate: SystemUI takes WallpaperColors' primary colour of the
            // *uncropped* art and snaps its hue to one of 18 fixed One UI wave presets.
            val seekBar = waveThemeColor(WallpaperColors.fromBitmap(readable).primaryColor.toArgb())
            // Same format as SystemUI's "Color" log, for side-by-side checks with `adb logcat -s Color PlaybarColors`.
            Log.i(
                TAG,
                "primaryColor: ${rgb(c.primary)}, secondaryColor: ${rgb(c.secondary)}, accentColor: ${rgb(c.accent)}" +
                    ", seekBar: #%06X".format(seekBar and 0xFFFFFF) +
                    "  [${key.replace('\u0000', '|')}, art ${art.width}x${art.height}]",
            )
            PlaybarColors(
                background = Color(c.primary),
                onBackground = PLAYER_CONTENT,
                accent = Color(seekBar),
            )
        }.onFailure { Log.w(TAG, "colour extraction failed", it) }
            .getOrNull()
            ?.also { cache.put(key, it) }
    }

    /**
     * One UI's `WaveSeekbar.setThemeByColor`: the hue (HSV, truncated to whole degrees) picks a
     * `WaveTheme`, and the seek bar's progress and thumb use that theme's first colour.
     */
    private fun waveThemeColor(color: Int): Int {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
        val h = hsv[0].toInt().coerceIn(0, 360)
        val hex = when {
            hsv[1] <= 0.1f || hsv[2] <= 0.1f -> 0x2D3855 // MONO
            h >= 350 || h < 6 -> 0xEA4147 // RED
            h < 21 -> 0xE52E00 // REDORANGE
            h < 36 -> 0xE55800 // ORANGE
            h < 51 -> 0xE58600 // ORANGEYELLOW
            h < 66 -> 0xFDE52F // YELLOW
            h < 81 -> 0xBFFD2F // YELLOWGREEN
            h < 117 -> 0x77FD2F // GREEN
            h < 151 -> 0x2FFD85 // GREENCYAN
            h < 176 -> 0x2FFDEC // CYAN
            h < 191 -> 0x2FD0FD // CYANBLUE
            h < 216 -> 0x2FA7FD // BLUE
            h < 241 -> 0x2F70FD // BLUENAVY
            h < 264 -> 0x2F4AFD // NAVY
            h < 286 -> 0x4E2FFD // NAVYVIOLET
            h < 308 -> 0x8E31FF // VIOLET
            h < 330 -> 0xDB2FFD // PURPLE
            else -> 0xFD2FB1 // PINK (330..349)
        }
        return hex or 0xFF000000.toInt()
    }

    /**
     * SystemUI crops the art to a centred square (what its player shows) before extracting
     * colours. Without this, 16:9 video thumbnails feed extra side pixels into Palette.
     */
    private fun centerSquare(b: Bitmap): Bitmap = when {
        b.width > b.height -> Bitmap.createBitmap(b, (b.width - b.height) / 2, 0, b.height, b.height)
        b.height > b.width -> Bitmap.createBitmap(b, 0, (b.height - b.width) / 2, b.width, b.width)
        else -> b
    }

    private fun base(bitmap: Bitmap): Extracted {
        val p = Palette.from(bitmap).maximumColorCount(24).generate()
        val primary = p.dominantSwatch?.rgb
            ?: p.mutedSwatch?.rgb
            ?: p.swatches.maxByOrNull { it.population }?.rgb
            ?: FALLBACK_PRIMARY
        val secondary = p.vibrantSwatch?.rgb
            ?: p.lightVibrantSwatch?.rgb
            ?: p.lightMutedSwatch?.rgb
            ?: runCatching { synth(primary, complementary = false, lighten = true) }.getOrDefault(FALLBACK_SECONDARY)
        val accent = p.darkVibrantSwatch?.rgb
            ?: p.darkMutedSwatch?.rgb
            ?: runCatching { synth(primary, complementary = true, lighten = false) }.getOrDefault(FALLBACK_ACCENT)
        return Extracted(primary, secondary, accent)
    }

    /** Samsung's `synthFrom`: rotate hue 60° (or 180°), boost saturation, optionally lighten. */
    private fun synth(color: Int, complementary: Boolean, lighten: Boolean): Int {
        val (h, s, l) = hsl(color)
        val hue = wrapHue(h + if (complementary) 180f else 60f)
        val light = if (lighten) minOf(l + 0.25f, 0.9f) else l
        return ColorUtils.HSLToColor(floatArrayOf(hue, (s + 0.2f).coerceIn(0.2f, 1f), light))
    }

    /** Samsung's `transformColor`: keep hues apart, then clamp each colour into its band. */
    private fun transform(e: Extracted): Extracted {
        val p = hsl(e.primary)
        val s = ensureHueGap(p, hsl(e.secondary), minGap = 35f, shift = 60f)
        val a = ensureHueGap(s, hsl(e.accent), minGap = 40f, shift = -60f)
        return Extracted(
            primary = ColorUtils.HSLToColor(floatArrayOf(p[0], p[1].coerceIn(0.10f, 0.45f), p[2].coerceIn(0.28f, 0.52f))),
            secondary = ColorUtils.HSLToColor(floatArrayOf(s[0], s[1].coerceIn(0.55f, 1f), s[2].coerceIn(0.62f, 0.92f))),
            accent = ColorUtils.HSLToColor(floatArrayOf(a[0], a[1].coerceIn(0.50f, 1f), a[2].coerceIn(0.32f, 0.55f))),
        )
    }

    /** If [other]'s hue is within [minGap] degrees of [ref]'s, rotate it by [shift]. */
    private fun ensureHueGap(ref: FloatArray, other: FloatArray, minGap: Float, shift: Float): FloatArray {
        val diff = abs(((ref[0] - other[0]) + 540f) % 360f - 180f)
        return if (diff < minGap) floatArrayOf(wrapHue(other[0] + shift), other[1], other[2]) else other
    }

    private fun rgb(c: Int) =
        "${android.graphics.Color.red(c)}, ${android.graphics.Color.green(c)}, ${android.graphics.Color.blue(c)}"

    private fun wrapHue(h: Float) = ((h % 360f) + 360f) % 360f

    private fun hsl(color: Int): FloatArray = FloatArray(3).also {
        ColorUtils.RGBToHSL(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color), it)
    }
}
