package photo

import kotlin.math.log10
import kotlin.math.max

/** Denoise controls as a user sees them; [spatial] and [range] are what the kernel needs. */
data class DenoiseSettings(val radius: Int = 6, val colorSigma: Float = 0.20f, val strength: Float = 1f) {
    val spatial: Float get() = 1f / (2f * (radius / 2f) * (radius / 2f))
    val range: Float get() = 1f / (2f * colorSigma * colorSigma)
}

/** Develop sliders; all zero means "no change". */
data class DevelopSettings(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val sharpen: Float = 0f,
    val vignette: Float = 0f,
)

/** The 24 ColorChecker Classic patches, sRGB, packed like pixels (red in the low byte). */
val COLOR_CHECKER: IntArray = intArrayOf(
    0x735244, 0xC29682, 0x627A9D, 0x576C43, 0x8580B1, 0x67BDAA,
    0xD67E2C, 0x505BA6, 0xC15A63, 0x5E3C6C, 0x9DBC40, 0xE0A32E,
    0x383D96, 0x469449, 0xAF363C, 0xE7C71F, 0xBB5695, 0x0885A1,
    0xF3F3F2, 0xC8C8C8, 0xA0A0A0, 0x7A7A79, 0x555555, 0x343434,
).map { rgb -> ((rgb shr 16) and 255) or (rgb and 0xFF00) or ((rgb and 255) shl 16) }.toIntArray()

/** Peak signal-to-noise ratio in dB from the per-row squared errors of [rowError]; higher is closer to the reference. */
fun psnr(rowErrors: FloatArray, width: Int, height: Int): Double {
    var sum = 0.0
    for (e in rowErrors) sum += e
    val mse = sum / (width.toDouble() * height * 3)
    return 10 * log10(1.0 / max(mse, 1e-12))
}
