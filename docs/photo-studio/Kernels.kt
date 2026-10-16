package photo

import io.github.rajumark.kernelin.Kernel
import io.github.rajumark.kernelin.globalId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A photo pipeline in plain Kotlin: denoise -> develop (exposure, white balance, tone, color, sharpening, vignette).
 * The JVM batch tool and the unit tests run these functions on the CPU; the browser app runs the very same
 * functions on the GPU, compiled to WebGPU compute shaders by the Kernelin compiler plugin.
 *
 * Pixels are packed like the browser's ImageData read as 32-bit ints: red in the low byte, then green, blue, alpha.
 */

/** Edge-preserving denoise (bilateral filter). Each output pixel is a weighted average of its (2r+1)² neighbours:
 * near and similar-colored neighbours count, pixels across an edge don't, so noise goes away and edges stay sharp.
 *
 * @param spatial 1 / (2 σs²): how fast weight falls with distance
 * @param range 1 / (2 σc²): how fast weight falls with color difference
 * @param strength 0 = original, 1 = fully filtered
 * @param first lets a big image run in batches: thread `t` handles pixel `first + t`
 */
@Kernel
fun denoise(
    src: IntArray, dst: IntArray, width: Int, height: Int,
    radius: Int, spatial: Float, range: Float, strength: Float, first: Int,
) {
    val i = globalId() + first
    val x = i % width
    val y = i / width
    val c = src[i]
    val r0 = red(c)
    val g0 = green(c)
    val b0 = blue(c)
    var sumR = 0f
    var sumG = 0f
    var sumB = 0f
    var sumW = 0f
    for (dy in -radius..radius) {
        val yy = (y + dy).coerceIn(0, height - 1)
        for (dx in -radius..radius) {
            val xx = (x + dx).coerceIn(0, width - 1)
            val p = src[yy * width + xx]
            val r = red(p)
            val g = green(p)
            val b = blue(p)
            val dr = r - r0
            val dg = g - g0
            val db = b - b0
            val w = exp(-(dx * dx + dy * dy) * spatial - (dr * dr + dg * dg + db * db) * range)
            sumR += w * r
            sumG += w * g
            sumB += w * b
            sumW += w
        }
    }
    val keep = 1f - strength
    dst[i] = pack(keep * r0 + strength * sumR / sumW, keep * g0 + strength * sumG / sumW, keep * b0 + strength * sumB / sumW)
}

/**
 * Develops a photo like a camera raw editor, in one pass per pixel: unsharp-mask sharpening (3x3), white balance and
 * exposure in linear light, shadows/highlights, contrast and saturation in display space, then a vignette.
 * All sliders are 0 for "no change" (sharpen 0..2, the rest about -1..1, exposure in stops).
 */
@Kernel
fun develop(
    src: IntArray, dst: IntArray, width: Int, height: Int,
    exposure: Float, contrast: Float, highlights: Float, shadows: Float,
    temperature: Float, tint: Float, saturation: Float, sharpen: Float, vignette: Float,
) {
    val i = globalId()
    val x = i % width
    val y = i / width
    val xl = max(x - 1, 0)
    val xr = min(x + 1, width - 1)
    val yu = max(y - 1, 0)
    val yd = min(y + 1, height - 1)
    val c = src[i]
    val n = src[yu * width + x]
    val s = src[yd * width + x]
    val w = src[y * width + xl]
    val e = src[y * width + xr]
    val nw = src[yu * width + xl]
    val ne = src[yu * width + xr]
    val sw = src[yd * width + xl]
    val se = src[yd * width + xr]

    // Unsharp mask: add back the difference between the pixel and its 3x3 Gaussian blur.
    val blurR = (4f * red(c) + 2f * (red(n) + red(s) + red(w) + red(e)) + red(nw) + red(ne) + red(sw) + red(se)) / 16f
    val blurG = (4f * green(c) + 2f * (green(n) + green(s) + green(w) + green(e)) + green(nw) + green(ne) + green(sw) + green(se)) / 16f
    val blurB = (4f * blue(c) + 2f * (blue(n) + blue(s) + blue(w) + blue(e)) + blue(nw) + blue(ne) + blue(sw) + blue(se)) / 16f
    var r = red(c) + sharpen * (red(c) - blurR)
    var g = green(c) + sharpen * (green(c) - blurG)
    var b = blue(c) + sharpen * (blue(c) - blurB)

    // Linear light: white balance and exposure are multiplications of the light that hit the sensor.
    val gain = exp(exposure * 0.6931472f)
    r = toLinear(r) * gain * (1f + 0.3f * temperature) * (1f + 0.1f * tint)
    g = toLinear(g) * gain * (1f - 0.2f * tint)
    b = toLinear(b) * gain * (1f - 0.3f * temperature) * (1f + 0.1f * tint)

    // Shadows lift dark areas, highlights act on bright ones, both by luminance so colors keep their hue.
    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
    val dark = 1f - min(lum * 4f, 1f)
    val bright = max(min(lum, 1f) - 0.5f, 0f) * 2f
    val toneGain = 1f + shadows * dark * dark * 1.5f + highlights * bright * bright * 0.6f
    r = toDisplay(r * toneGain)
    g = toDisplay(g * toneGain)
    b = toDisplay(b * toneGain)

    // Contrast around middle gray, then saturation around the pixel's own gray.
    r = (r - 0.5f) * (1f + contrast) + 0.5f
    g = (g - 0.5f) * (1f + contrast) + 0.5f
    b = (b - 0.5f) * (1f + contrast) + 0.5f
    val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
    r = gray + (r - gray) * (1f + saturation)
    g = gray + (g - gray) * (1f + saturation)
    b = gray + (b - gray) * (1f + saturation)

    // Vignette: darken (negative) or lighten (positive) towards the corners.
    val u = (x.toFloat() / width - 0.5f) * 2f
    val v = (y.toFloat() / height - 0.5f) * 2f
    val fall = 1f + vignette * (u * u + v * v) * 0.4f
    dst[i] = pack(r * fall, g * fall, b * fall)
}

/** Squared error of each image row, summed over pixels and channels (0..1 units): host adds rows up for PSNR. */
@Kernel
fun rowError(a: IntArray, b: IntArray, out: FloatArray, width: Int) {
    val row = globalId()
    var sum = 0f
    for (x in 0 until width) {
        val p = a[row * width + x]
        val q = b[row * width + x]
        val dr = red(p) - red(q)
        val dg = green(p) - green(q)
        val db = blue(p) - blue(q)
        sum += dr * dr + dg * dg + db * db
    }
    out[row] = sum
}

/**
 * Draws an image-quality test chart: the 24 ColorChecker patches ([palette], packed RGB), a 36-spoke Siemens star
 * for sharpness, a zone plate whose rings get finer up to the pixel limit, a smooth ramp and an 11-step gray wedge.
 */
@Kernel
fun testChart(out: IntArray, palette: IntArray, width: Int, height: Int) {
    val i = globalId()
    val x = i % width
    val y = i / width
    val u = x.toFloat() / width
    val v = y.toFloat() / height
    val h = height.toFloat()
    var value = 0.22f
    var color = -1
    if (v > 0.86f) {
        value = if (v < 0.93f) u else ((u * 11f).toInt().coerceAtMost(10)) / 10f
    } else if (u < 0.62f) {
        val cu = (u - 0.03f) / 0.56f * 6f
        val cv = (v - 0.05f) / 0.77f * 4f
        if (cu >= 0f && cv >= 0f && cu < 6f && cv < 4f) {
            val fu = cu - cu.toInt()
            val fv = cv - cv.toInt()
            if (fu > 0.08f && fu < 0.92f && fv > 0.08f && fv < 0.92f) color = palette[cv.toInt() * 6 + cu.toInt()]
        }
    } else if (v < 0.43f) {
        val dx = x - 0.81f * width
        val dy = y - 0.235f * h
        if (dx * dx + dy * dy < (0.19f * h) * (0.19f * h)) value = if (sin(36f * atan2(dy, dx)) > 0f) 0.92f else 0.08f
    } else {
        val dx = x - 0.81f * width
        val dy = y - 0.645f * h
        val radius = 0.2f * h
        val r2 = dx * dx + dy * dy
        if (r2 < radius * radius) value = 0.5f + 0.43f * cos(1.4137167f / radius * r2) // 0.45 pi: rings reach ~0.45 cycles/px
    }
    out[i] = if (color == -1) pack(value, value, value) else (color or -16777216)
}

/**
 * Simulates the noise of a small camera sensor at a given ISO: photon shot noise (grows with the light), read noise
 * (constant), independent per color channel, so it shows both luminance and color blotches like a real high-ISO photo.
 */
@Kernel
fun addSensorNoise(clean: IntArray, noisy: IntArray, iso: Float, seed: Int) {
    val i = globalId()
    val c = clean[i]
    val shot = 0.012f * iso / 6400f
    val read = 0.006f * iso / 6400f
    val key = hashMix(i * -1640531527 + seed)
    val r = toLinear(red(c))
    val g = toLinear(green(c))
    val b = toLinear(blue(c))
    val nr = r + gaussian(key, 0) * sqrt(r * shot + read * read)
    val ng = g + gaussian(key, 2) * sqrt(g * shot + read * read)
    val nb = b + gaussian(key, 4) * sqrt(b * shot + read * read)
    noisy[i] = pack(toDisplay(nr), toDisplay(ng), toDisplay(nb))
}

fun red(p: Int): Float = (p and 255) / 255f

fun green(p: Int): Float = ((p ushr 8) and 255) / 255f

fun blue(p: Int): Float = ((p ushr 16) and 255) / 255f

/** Packs 0..1 channels into an opaque pixel, rounding and clipping like a real encoder. */
fun pack(r: Float, g: Float, b: Float): Int {
    val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
    val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
    val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
    return ri or (gi shl 8) or (bi shl 16) or -16777216 // alpha 255
}

/** Display (gamma 2.2) value to linear light. */
fun toLinear(x: Float): Float = max(x, 0f).pow(2.2f)

/** Linear light back to a display value. */
fun toDisplay(x: Float): Float = max(x, 0f).pow(0.45454547f)

/** MurmurHash3's 32-bit finalizer. */
fun hashMix(x: Int): Int {
    var h = x
    h = h xor (h ushr 16)
    h *= -2048144789
    h = h xor (h ushr 13)
    h *= -1028477387
    h = h xor (h ushr 16)
    return h
}

/** Standard normal random number [n] for a pixel's [key] (Box-Muller). */
fun gaussian(key: Int, n: Int): Float {
    val u1 = ((hashMix(key + n * 668265263) ushr 8).toFloat() + 0.5f) * 5.9604645E-8f
    val u2 = ((hashMix(key + (n + 1) * 668265263) ushr 8).toFloat() + 0.5f) * 5.9604645E-8f
    return sqrt(-2f * ln(u1)) * cos(6.2831855f * u2)
}
