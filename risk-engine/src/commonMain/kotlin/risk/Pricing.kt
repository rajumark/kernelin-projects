package risk

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Market and contract terms. Rates and volatility are yearly, maturity is in years, steps are monitoring days. */
data class Market(
    val spot: Double = 100.0,
    val strike: Double = 100.0,
    val rate: Double = 0.03,
    val vol: Double = 0.25,
    val maturity: Double = 1.0,
    val barrier: Double = 140.0,
    val steps: Int = 252,
) {
    fun withSpot(s: Double) = copy(spot = s)
    fun withVol(v: Double) = copy(vol = v)
}

/** A Monte Carlo price with its standard error (the 95% confidence interval is ±1.96 standard errors). */
data class Estimate(val price: Double, val stdError: Double)

/** Paths simulated by each GPU thread. More per thread means fewer, longer threads. */
const val PATHS_PER_THREAD = 32

/** Something that runs [pricePaths] for `threads` threads and returns the raw per-thread sums. */
fun interface PathEngine {
    suspend fun run(market: Market, threads: Int, seed: Int): FloatArray
}

/** Prices of every product for one market, from the raw per-thread sums. */
class BookPrices(val products: List<Estimate>, val arithmeticWithControl: Estimate, val paths: Int)

fun aggregate(raw: FloatArray, threads: Int, market: Market): BookPrices {
    val n = threads.toDouble() * PATHS_PER_THREAD
    // Sums are added up in Double on the host: each thread's Float partial sum covers only 32 paths.
    val sums = DoubleArray(OUTPUTS_PER_THREAD)
    for (t in 0 until threads) for (j in 0 until OUTPUTS_PER_THREAD) sums[j] += raw[t * OUTPUTS_PER_THREAD + j]
    val products = (0 until 6).map { j ->
        val mean = sums[j] / n
        val variance = max(sums[j + 6] / n - mean * mean, 0.0)
        Estimate(mean, sqrt(variance / n))
    }
    // Control variate: the geometric Asian has an exact price, so use its error to correct the arithmetic Asian.
    val meanA = products[3].price
    val meanG = products[2].price
    val varA = sums[9] / n - meanA * meanA
    val varG = sums[8] / n - meanG * meanG
    val cov = sums[12] / n - meanA * meanG
    val beta = if (varG > 0) cov / varG else 0.0
    val controlled = meanA - beta * (meanG - Analytic.geometricAsianCall(market))
    val controlledVar = max(varA - cov * cov / max(varG, 1e-30), 0.0)
    return BookPrices(products, Estimate(controlled, sqrt(controlledVar / n)), n.toInt())
}

/** Price, Delta, Gamma and Vega of every product, by bump-and-reprice on the same random numbers. */
class RiskReport(
    val market: Market,
    val base: BookPrices,
    val delta: List<Double>,
    val gamma: List<Double>,
    val vega: List<Double>,
    val millis: Double,
    val engine: String,
)

suspend fun riskReport(engine: PathEngine, market: Market, paths: Int, seed: Int, name: String, clock: () -> Double): RiskReport {
    val threads = (paths + PATHS_PER_THREAD - 1) / PATHS_PER_THREAD
    val h = market.spot * 0.01
    val start = clock()
    val base = aggregate(engine.run(market, threads, seed), threads, market)
    val up = aggregate(engine.run(market.withSpot(market.spot + h), threads, seed), threads, market.withSpot(market.spot + h))
    val down = aggregate(engine.run(market.withSpot(market.spot - h), threads, seed), threads, market.withSpot(market.spot - h))
    val volUp = aggregate(engine.run(market.withVol(market.vol + 0.01), threads, seed), threads, market.withVol(market.vol + 0.01))
    val millis = clock() - start
    val p = { b: BookPrices, j: Int -> b.products[j].price }
    return RiskReport(
        market, base,
        delta = (0 until 6).map { (p(up, it) - p(down, it)) / (2 * h) },
        gamma = (0 until 6).map { (p(up, it) - 2 * p(base, it) + p(down, it)) / (h * h) },
        vega = (0 until 6).map { p(volUp, it) - p(base, it) },
        millis = millis, engine = name,
    )
}

/** Closed-form prices: the reference every Monte Carlo result is checked against. */
object Analytic {
    private fun d1(m: Market) = (ln(m.spot / m.strike) + (m.rate + m.vol * m.vol / 2) * m.maturity) / (m.vol * sqrt(m.maturity))

    fun call(m: Market): Double {
        val d1 = d1(m)
        val d2 = d1 - m.vol * sqrt(m.maturity)
        return m.spot * normCdf(d1) - m.strike * exp(-m.rate * m.maturity) * normCdf(d2)
    }

    fun put(m: Market): Double = call(m) - m.spot + m.strike * exp(-m.rate * m.maturity)

    fun callDelta(m: Market): Double = normCdf(d1(m))

    fun callGamma(m: Market): Double = normPdf(d1(m)) / (m.spot * m.vol * sqrt(m.maturity))

    /** Change in price for +1 volatility point (0.01). */
    fun callVega(m: Market): Double = m.spot * normPdf(d1(m)) * sqrt(m.maturity) * 0.01

    /** Discretely monitored geometric average price call (average of the `steps` daily prices after today). */
    fun geometricAsianCall(m: Market): Double {
        val n = m.steps.toDouble()
        val t = m.maturity
        val mu = ln(m.spot) + (m.rate - m.vol * m.vol / 2) * t * (n + 1) / (2 * n)
        val sigma2 = m.vol * m.vol * t * (n + 1) * (2 * n + 1) / (6 * n * n)
        val sigma = sqrt(sigma2)
        val d1 = (mu - ln(m.strike) + sigma2) / sigma
        val d2 = d1 - sigma
        return exp(-m.rate * t) * (exp(mu + sigma2 / 2) * normCdf(d1) - m.strike * normCdf(d2))
    }

    fun normPdf(x: Double): Double = exp(-x * x / 2) / sqrt(2 * PI)

    /** Standard normal CDF, accurate to about 1e-15. */
    fun normCdf(x: Double): Double = 0.5 * erfc(-x / sqrt(2.0))

    private fun erfc(x: Double): Double {
        // Taylor series of erf near 0, continued fraction for erfc elsewhere.
        val z = abs(x)
        val r = if (z < 0.5) {
            1 - erfSeries(z)
        } else {
            erfcContinuedFraction(z)
        }
        return if (x >= 0) r else 2 - r
    }

    private fun erfSeries(z: Double): Double {
        var term = z
        var sum = z
        var n = 0
        while (abs(term) > 1e-17 * abs(sum)) {
            n++
            term *= -z * z / n
            sum += term / (2 * n + 1)
        }
        return 2 / sqrt(PI) * sum
    }

    private fun erfcContinuedFraction(z: Double): Double {
        // Lentz's method for erfc(z) = exp(-z^2)/sqrt(pi) * 1/(z + 1/2/(z + 1/(z + 3/2/(z + ...))))
        val tiny = 1e-300
        var f = z
        var c = z
        var d = 0.0
        for (i in 1..300) {
            val a = i / 2.0
            d = z + a * d
            d = if (abs(d) < tiny) tiny else d
            c = z + a / c
            c = if (abs(c) < tiny) tiny else c
            d = 1 / d
            val delta = c * d
            f *= delta
            if (abs(delta - 1) < 1e-16) break
        }
        return exp(-z * z) / sqrt(PI) / f
    }
}

/** The report as JSON for the web page. */
fun RiskReport.toJson(): String = buildString {
    val m = market
    append("{\"engine\":\"").append(engine).append("\",\"millis\":").append(millis)
    append(",\"paths\":").append(base.paths).append(",\"steps\":").append(m.steps)
    append(",\"products\":[")
    for (j in 0 until 6) {
        if (j > 0) append(',')
        val e = base.products[j]
        val analytic = when (j) {
            0 -> Analytic.call(m)
            1 -> Analytic.put(m)
            2 -> Analytic.geometricAsianCall(m)
            else -> null
        }
        append("{\"name\":\"").append(PRODUCT_NAMES[j]).append("\",\"price\":").append(e.price)
        append(",\"se\":").append(e.stdError).append(",\"analytic\":").append(analytic?.toString() ?: "null")
        append(",\"delta\":").append(delta[j]).append(",\"gamma\":").append(gamma[j]).append(",\"vega\":").append(vega[j]).append('}')
    }
    append("],\"arithmeticCv\":{\"price\":").append(base.arithmeticWithControl.price)
    append(",\"se\":").append(base.arithmeticWithControl.stdError).append('}')
    append(",\"callGreeks\":{\"delta\":").append(Analytic.callDelta(m)).append(",\"gamma\":").append(Analytic.callGamma(m))
    append(",\"vega\":").append(Analytic.callVega(m)).append("}}")
}
