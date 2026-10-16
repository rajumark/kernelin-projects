package motion

import io.github.rajumark.kernelin.Kernel
import io.github.rajumark.kernelin.globalId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Motion estimation for traffic and security cameras, in plain Kotlin. Like a video encoder, it finds where every
 * 16x16 block of the current frame came from in the previous frame (block matching), which gives a motion vector per
 * block: what moves, which way, and how fast. Swaying leaves and light changes give tiny or zero vectors; cars and
 * people give clear, consistent ones.
 *
 * The JVM tool and unit tests run these functions on the CPU; the browser runs them on the GPU, compiled to WebGPU
 * compute shaders by the Kernelin compiler plugin. Pixels are packed like ImageData: red in the low byte.
 */

/** Block size in pixels. */
const val BLOCK = 16

/**
 * Block matching by exhaustive search. Thread `t` handles block `t / (2 range + 1)` and one vertical offset
 * `dy = t % (2 range + 1) - range`, and tries every horizontal offset `dx` in `-range..range`: it adds up the absolute
 * brightness differences (SAD) between the block in [cur] and the block shifted by (-dx, -dy) in [prev].
 * It writes 3 ints: the best SAD for its row, that `dx`, and the SAD at `dx = 0` (no motion).
 * The host picks the best row per block ([analyzeBlocks]).
 */
@Kernel
fun matchBlocks(prev: IntArray, cur: IntArray, out: IntArray, width: Int, height: Int, blocksX: Int, range: Int) {
    val t = globalId()
    val span = 2 * range + 1
    val block = t / span
    val dy = t % span - range
    val bx = (block % blocksX) * BLOCK
    val by = (block / blocksX) * BLOCK
    var best = 2147483647
    var bestDx = 0
    var zero = 0
    for (dx in -range..range) {
        var sad = 0
        for (j in 0 until BLOCK) {
            val y = min(by + j, height - 1)
            val py = (y - dy).coerceIn(0, height - 1)
            for (k in 0 until BLOCK) {
                val x = min(bx + k, width - 1)
                val px = (x - dx).coerceIn(0, width - 1)
                sad += abs(luma8(cur[y * width + x]) - luma8(prev[py * width + px]))
            }
        }
        if (dx == 0) zero = sad
        // On a tie, the smaller motion wins: flat areas then report "no motion" instead of a random vector.
        if (sad < best || (sad == best && abs(dx) < abs(bestDx))) {
            best = sad
            bestDx = dx
        }
    }
    out[3 * t] = best
    out[3 * t + 1] = bestDx
    out[3 * t + 2] = zero
}

/**
 * Renders frame [frameIndex] of a synthetic parking-lot camera: building, asphalt and lane marks, a tree whose leaves
 * sway (motion that must NOT trigger alarms), slowly changing daylight, three cars, two pedestrians and sensor noise.
 * [noiseSeed] picks the camera noise (normally the frame index).
 * Everything that moves is known exactly ([objectAt], [objectVelocity]), so detection accuracy and vector error
 * can be measured.
 */
@Kernel
fun renderScene(out: IntArray, width: Int, height: Int, frameIndex: Int, noise: Float, noiseSeed: Int) {
    val i = globalId()
    val x = i % width
    val y = i / width
    val time = frameIndex / 30f
    val u = x.toFloat() / width
    val v = y.toFloat() / height
    var r = 0f
    var g = 0f
    var b = 0f
    if (v < 0.18f) {
        r = 150f + 60f * v
        g = 170f + 50f * v
        b = 200f + 20f * v
    } else if (v < 0.38f) {
        val window = x % 60 >= 15 && x % 60 < 45 && y % 40 >= 10 && y % 40 < 30
        r = if (window) 40f else 122f
        g = if (window) 55f else 110f
        b = if (window) 72f else 98f
    } else {
        val grain = (hash2(x / 2, y / 2) and 15) - 8f
        r = 86f + grain
        g = 86f + grain
        b = 90f + grain
        val lane = (abs(v - 0.62f) < 0.004f || abs(v - 0.81f) < 0.004f) && x % 120 < 70
        if (lane) {
            r = 215f
            g = 212f
            b = 200f
        }
    }
    // Tree: leaves sway, the top more than the bottom.
    val treeX = 0.1f * width
    val treeY = 0.43f * height
    val treeR = 0.13f * height
    if ((x - treeX) * (x - treeX) + (y - treeY) * (y - treeY) < treeR * treeR) {
        val sway = 7f * sin(time * 2.1f) * (treeY + treeR - y) / (2f * treeR)
        val leaf = (hash2(((x + sway) / 5f).toInt(), y / 5) and 63).toFloat()
        r = 35f + leaf * 0.5f
        g = 85f + leaf
        b = 35f + leaf * 0.3f
    } else if (abs(x - treeX) < 0.012f * width && y > treeY && v < 0.62f) {
        r = 80f
        g = 58f
        b = 40f
    }
    val id = objectAt(x, y, width, height, time)
    if (id > 0) {
        val tone = objectTone(id, x, y, width, height, time)
        r = red(tone) * 255f
        g = green(tone) * 255f
        b = blue(tone) * 255f
    }
    // Daylight drifts by ±6% as clouds pass; the camera adds noise to every pixel, every frame.
    val light = 1f + 0.06f * sin(time * 0.35f)
    val key = hash2(i, noiseSeed * 7919 + 17)
    out[i] = rgb(
        r * light + noise * gauss(key, 0),
        g * light + noise * gauss(key, 2),
        b * light + noise * gauss(key, 4),
    )
}

/** Which moving object covers pixel (x, y) at [time] seconds: 1-3 cars, 4-5 pedestrians, 0 none. */
fun objectAt(x: Int, y: Int, width: Int, height: Int, time: Float): Int {
    val u = x.toFloat() / width
    val v = y.toFloat() / height
    var id = 0
    for (car in 1..3) {
        val lane = if (car == 1) 0.67f else if (car == 2) 0.75f else 0.89f
        val left = carLeft(car, time)
        if (u > left && u < left + 0.15f && v > lane - 0.035f && v < lane + 0.035f) id = car
    }
    for (p in 4..5) {
        val pu = walkerU(p, time)
        val pv = walkerV(p, time)
        val du = (u - pu) / 0.011f
        val dv = (v - pv) / 0.045f
        if (du * du + dv * dv < 1f) id = p
    }
    return id
}

/** Left edge (fraction of width) of a car; cars drive right or left and wrap around. */
fun carLeft(car: Int, time: Float): Float {
    val speed = if (car == 1) 0.09f else if (car == 2) -0.06f else 0.13f
    val phase = if (car == 1) 0.1f else if (car == 2) 0.7f else 0.45f
    return wrap01(phase + speed * time) * 1.5f - 0.25f
}

fun walkerU(p: Int, time: Float): Float = if (p == 4) 0.3f + 0.5f * wrap01(0.1f * time + 0.2f) else 0.85f - 0.4f * wrap01(0.12f * time + 0.6f)

fun walkerV(p: Int, time: Float): Float = if (p == 4) 0.45f + 0.12f * wrap01(0.1f * time + 0.2f) else 0.5f + 0.06f * wrap01(0.12f * time + 0.6f)

/** Color of a moving object: car bodies with dark windows, people with shirts and darker legs. */
fun objectTone(id: Int, x: Int, y: Int, width: Int, height: Int, time: Float): Int {
    val v = y.toFloat() / height
    var tone = 0
    if (id >= 4) {
        val pv = walkerV(id, time)
        tone = if (v < pv - 0.03f) rgb(205f, 165f, 140f)
        else if (v > pv + 0.005f) rgb(40f, 45f, 70f)
        else if (id == 4) rgb(200f, 60f, 50f)
        else rgb(240f, 200f, 60f)
        // Fabric texture that moves with the person.
        val fx = ((x.toFloat() - walkerU(id, time) * width) / 3f + 100f).toInt()
        val fy = ((y.toFloat() - pv * height) / 3f + 100f).toInt()
        tone = shade(tone, (hash2(fx, fy) and 31) - 16)
    } else {
        val lane = if (id == 1) 0.67f else if (id == 2) 0.75f else 0.89f
        val along = (x.toFloat() / width - carLeft(id, time)) / 0.15f
        tone = if (v < lane - 0.01f && along > 0.25f && along < 0.75f) rgb(30f, 40f, 55f)
        else if (id == 1) rgb(190f, 30f, 35f)
        else if (id == 2) rgb(225f, 225f, 230f)
        else rgb(30f, 80f, 170f)
        // Reflections and dirt that move with the car.
        val cx = ((x.toFloat() / width - carLeft(id, time)) * width / 4f).toInt()
        tone = shade(tone, (hash2(cx, y / 4) and 31) - 16)
    }
    return tone
}

/** Brightens or darkens a pixel by [delta] levels. */
fun shade(p: Int, delta: Int): Int = rgb(
    ((p and 255) + delta).toFloat(),
    (((p ushr 8) and 255) + delta).toFloat(),
    (((p ushr 16) and 255) + delta).toFloat(),
)

/** Brightness 0..255 with integer math (Rec. 709 weights / 256). */
fun luma8(p: Int): Int = (54 * (p and 255) + 183 * ((p ushr 8) and 255) + 19 * ((p ushr 16) and 255)) ushr 8

fun wrap01(x: Float): Float {
    val f = x - x.toInt().toFloat()
    return if (f < 0f) f + 1f else f
}

fun red(p: Int): Float = (p and 255) / 255f

fun green(p: Int): Float = ((p ushr 8) and 255) / 255f

fun blue(p: Int): Float = ((p ushr 16) and 255) / 255f

/** Opaque pixel from 0..255 channels. */
fun rgb(r: Float, g: Float, b: Float): Int =
    (r + 0.5f).toInt().coerceIn(0, 255) or ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
        ((b + 0.5f).toInt().coerceIn(0, 255) shl 16) or -16777216

/** Two-input integer hash (MurmurHash3 finalizer). */
fun hash2(a: Int, b: Int): Int {
    var h = a * -1640531527 + b * 668265263
    h = h xor (h ushr 16)
    h *= -2048144789
    h = h xor (h ushr 13)
    h *= -1028477387
    h = h xor (h ushr 16)
    return h
}

/** Standard normal number [n] for [key] (Box-Muller). */
fun gauss(key: Int, n: Int): Float {
    val u1 = ((hash2(key, n) ushr 8).toFloat() + 0.5f) * 5.9604645E-8f
    val u2 = ((hash2(key, n + 1) ushr 8).toFloat() + 0.5f) * 5.9604645E-8f
    return sqrt(-2f * ln(u1)) * cos(6.2831855f * u2)
}
