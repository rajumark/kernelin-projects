package risk

import io.github.rajumark.kernelin.Kernel
import io.github.rajumark.kernelin.globalId
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Monte Carlo pricing of an options book under geometric Brownian motion (the Black-Scholes model),
 * simulated day by day so path-dependent products (Asian, barrier, lookback) can be priced.
 *
 * This file is the whole numerical engine. It is plain Kotlin: unit tests and the JVM CLI run it on the CPU,
 * and the Kernelin compiler plugin turns pricePaths into a WebGPU compute shader for the browser app.
 */

/** Number of values each GPU thread writes: 6 payoff sums, 6 sums of squares, 1 cross term. */
const val OUTPUTS_PER_THREAD = 13

/** Products priced on every path, in output order. */
val PRODUCT_NAMES = listOf(
    "European call",
    "European put",
    "Asian call (geometric)",
    "Asian call (arithmetic)",
    "Up-and-out barrier call",
    "Lookback call (floating strike)",
)

/**
 * One thread simulates [pathsPerThread] independent price paths of [steps] days each and adds up the discounted
 * payoffs of all six products. Thread `t` writes `out[t * 13 + j]`:
 * - `j = 0..5`: sum of payoffs, `j = 6..11`: sum of squared payoffs (for the standard error),
 * - `j = 12`: sum of arithmetic Asian payoff × geometric Asian payoff (for the control variate).
 *
 * [threadOffset] lets a big job run in batches: thread `t` simulates the paths of thread `t + threadOffset`.
 *
 * Random numbers are counter based: path `p`, draw `c` always gets the same value on CPU and GPU,
 * however the work is split into threads, and paths never share or overlap random streams.
 */
@Kernel
fun pricePaths(
    out: FloatArray,
    spot: Float,
    strike: Float,
    rate: Float,
    vol: Float,
    maturity: Float,
    barrier: Float,
    steps: Int,
    pathsPerThread: Int,
    seed: Int,
    threadOffset: Int,
) {
    val t = globalId()
    val dt = maturity / steps
    val drift = (rate - 0.5f * vol * vol) * dt
    val diffusion = vol * sqrt(dt)
    val discount = exp(-rate * maturity)
    val logSpot = ln(spot)

    var sumCall = 0f
    var sumPut = 0f
    var sumGeo = 0f
    var sumArith = 0f
    var sumBarrier = 0f
    var sumLookback = 0f
    var sqCall = 0f
    var sqPut = 0f
    var sqGeo = 0f
    var sqArith = 0f
    var sqBarrier = 0f
    var sqLookback = 0f
    var cross = 0f

    for (p in 0 until pathsPerThread) {
        val key = pathKey((t + threadOffset) * pathsPerThread + p, seed)
        var logS = logSpot
        var sumS = 0f
        var sumLogS = 0f
        var minS = spot
        var alive = true
        var spare = 0f
        for (k in 0 until steps) {
            // Box-Muller gives two normals from two uniforms: use the second one on the next day.
            var z = spare
            if (k % 2 == 0) {
                val u1 = random01(key, k)
                val u2 = random01(key, k + 1)
                val r = sqrt(-2f * ln(u1))
                val angle = 6.2831855f * u2
                z = r * cos(angle)
                spare = r * sin(angle)
            }
            logS += drift + diffusion * z
            val s = exp(logS)
            sumS += s
            sumLogS += logS
            minS = min(minS, s)
            if (s >= barrier) alive = false
        }
        val last = exp(logS)
        val call = discount * max(last - strike, 0f)
        val put = discount * max(strike - last, 0f)
        val geo = discount * max(exp(sumLogS / steps) - strike, 0f)
        val arith = discount * max(sumS / steps - strike, 0f)
        val knockOut = if (alive) call else 0f
        val lookback = discount * (last - minS)

        sumCall += call
        sumPut += put
        sumGeo += geo
        sumArith += arith
        sumBarrier += knockOut
        sumLookback += lookback
        sqCall += call * call
        sqPut += put * put
        sqGeo += geo * geo
        sqArith += arith * arith
        sqBarrier += knockOut * knockOut
        sqLookback += lookback * lookback
        cross += arith * geo
    }

    val o = t * OUTPUTS_PER_THREAD
    out[o] = sumCall
    out[o + 1] = sumPut
    out[o + 2] = sumGeo
    out[o + 3] = sumArith
    out[o + 4] = sumBarrier
    out[o + 5] = sumLookback
    out[o + 6] = sqCall
    out[o + 7] = sqPut
    out[o + 8] = sqGeo
    out[o + 9] = sqArith
    out[o + 10] = sqBarrier
    out[o + 11] = sqLookback
    out[o + 12] = cross
}

/** Writes [paths] full price paths (steps + 1 points each) for the fan chart, using the pricing random numbers. */
@Kernel
fun samplePaths(out: FloatArray, spot: Float, rate: Float, vol: Float, maturity: Float, steps: Int, seed: Int) {
    val path = globalId()
    val dt = maturity / steps
    val drift = (rate - 0.5f * vol * vol) * dt
    val diffusion = vol * sqrt(dt)
    val key = pathKey(path, seed)
    var logS = ln(spot)
    var spare = 0f
    val base = path * (steps + 1)
    out[base] = spot
    for (k in 0 until steps) {
        var z = spare
        if (k % 2 == 0) {
            val r = sqrt(-2f * ln(random01(key, k)))
            val angle = 6.2831855f * random01(key, k + 1)
            z = r * cos(angle)
            spare = r * sin(angle)
        }
        logS += drift + diffusion * z
        out[base + k + 1] = exp(logS)
    }
}

/** MurmurHash3's 32-bit finalizer: a fast bijective mix where every input bit affects every output bit. */
fun fmix(x: Int): Int {
    var h = x
    h = h xor (h ushr 16)
    h *= -2048144789 // 0x85ebca6b
    h = h xor (h ushr 13)
    h *= -1028477387 // 0xc2b2ae35
    h = h xor (h ushr 16)
    return h
}

/** The random stream key of one path. */
fun pathKey(path: Int, seed: Int): Int = fmix(fmix(path * -1640531527 + seed) xor 1013904223)

/** Uniform random number in (0, 1) for draw [counter] of the path with [key]; never exactly 0, so ln() is safe. */
fun random01(key: Int, counter: Int): Float {
    val h = fmix(key + counter * -1640531527) // 0x9e3779b9, the golden ratio
    return ((h ushr 8).toFloat() + 0.5f) * 5.9604645E-8f // 2^-24
}
