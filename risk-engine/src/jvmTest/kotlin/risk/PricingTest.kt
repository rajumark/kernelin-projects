package risk

import io.github.rajumark.kernelin.KernelinShaders
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The kernels are tested here as plain Kotlin on the CPU. The browser runs the very same functions on the GPU,
 * so these checks are what lets you trust the GPU numbers.
 */
class PricingTest {
    private val market = Market()
    private val paths = 131_072
    private val prices by lazy {
        val threads = paths / PATHS_PER_THREAD
        aggregate(runBlocking { CpuEngine(parallel = true).run(market, threads, seed = 7) }, threads, market)
    }

    /** Monte Carlo is random: a correct engine lands within 4 standard errors of the exact price (99.99%). */
    private fun assertWithin(exact: Double, e: Estimate, what: String) {
        val z = abs(e.price - exact) / e.stdError
        println("%-28s MC %.4f ± %.4f, exact %.4f, z = %.2f".format(what, e.price, e.stdError, exact, z))
        assertTrue(z < 4.0, "$what: ${e.price} is $z standard errors from the exact $exact")
    }

    @Test
    fun `reference formulas match textbook values`() {
        // Hull, Options Futures and Other Derivatives: S=42, K=40, r=10%, vol=20%, T=0.5 gives call 4.76, put 0.81.
        val hull = Market(spot = 42.0, strike = 40.0, rate = 0.10, vol = 0.20, maturity = 0.5)
        assertEquals(4.7594, Analytic.call(hull), 1e-4)
        assertEquals(0.8086, Analytic.put(hull), 1e-4)
        assertEquals(0.5, Analytic.normCdf(0.0), 1e-15)
        assertEquals(0.9750021048517795, Analytic.normCdf(1.96), 1e-13)
        assertEquals(2.866515718791939e-7, Analytic.normCdf(-5.0), 1e-19)
    }

    @Test
    fun `European call and put match Black-Scholes`() {
        assertWithin(Analytic.call(market), prices.products[0], "European call")
        assertWithin(Analytic.put(market), prices.products[1], "European put")
    }

    @Test
    fun `geometric Asian matches its closed form`() {
        assertWithin(Analytic.geometricAsianCall(market), prices.products[2], "Asian call (geometric)")
    }

    @Test
    fun `put-call parity holds path by path`() {
        // C - P = S - K e^(-rT) exactly in expectation; on the same paths only the sampling error of S_T remains.
        val parity = prices.products[0].price - prices.products[1].price
        assertEquals(market.spot - market.strike * exp(-market.rate * market.maturity), parity, 0.15)
    }

    @Test
    fun `product prices are ordered as theory says`() {
        val p = prices.products.map { it.price }
        assertTrue(p[4] < p[0], "a knock-out call is worth less than the vanilla call")
        assertTrue(p[2] < p[3], "geometric average <= arithmetic average")
        assertTrue(p[3] < p[0], "averaging lowers volatility, so the Asian call is cheaper")
        assertTrue(p[5] > p[0], "a floating-strike lookback is worth more than an at-the-money call")
    }

    @Test
    fun `control variate cuts the arithmetic Asian error`() {
        val plain = prices.products[3].stdError
        val controlled = prices.arithmeticWithControl.stdError
        println("Arithmetic Asian standard error: plain %.5f, with control variate %.5f".format(plain, controlled))
        assertTrue(controlled < plain / 5, "the control variate should remove most of the noise")
    }

    @Test
    fun `greeks match the analytic European greeks`() = runBlocking {
        val report = riskReport(CpuEngine(parallel = true), market, 262_144, seed = 11, name = "test") { 0.0 }
        assertEquals(Analytic.callDelta(market), report.delta[0], 0.01)
        assertEquals(Analytic.callVega(market), report.vega[0], 0.02)
        assertEquals(Analytic.callGamma(market), report.gamma[0], 0.005)
    }

    @Test
    fun `random numbers are uniform and independent of thread layout`() {
        var sum = 0.0
        var sumSq = 0.0
        val n = 1_000_000
        for (i in 0 until n) {
            val u = random01(pathKey(i / 100, 1), i % 100).toDouble()
            assertTrue(u > 0.0 && u < 1.0)
            sum += u
            sumSq += u * u
        }
        val mean = sum / n
        assertEquals(0.5, mean, 0.002)
        assertEquals(1.0 / 12, sumSq / n - mean * mean, 0.001)

        // Same paths, different split into threads: identical sums.
        val small = market.copy(steps = 16)
        val a = runBlocking { CpuEngine(false).run(small, 4, 3) }
        val b = FloatArray(OUTPUTS_PER_THREAD * 4)
        io.github.rajumark.kernelin.KernelinCpu.dispatch(4) {
            pricePaths(b, 100f, 100f, 0.03f, 0.25f, 1f, 140f, 16, PATHS_PER_THREAD, 3, 0)
        }
        assertTrue(a.contentEquals(b))
        // Running threads 2..3 as a batch with threadOffset = 2 gives the same numbers as the full run.
        val batch = FloatArray(OUTPUTS_PER_THREAD * 2)
        io.github.rajumark.kernelin.KernelinCpu.dispatch(2) {
            pricePaths(batch, 100f, 100f, 0.03f, 0.25f, 1f, 140f, 16, PATHS_PER_THREAD, 3, 2)
        }
        assertTrue(batch.contentEquals(b.copyOfRange(OUTPUTS_PER_THREAD * 2, OUTPUTS_PER_THREAD * 4)))
    }

    @Test
    fun `Kernelin generated a GPU shader for both kernels`() {
        // The plugin ships each kernel's WGSL next to the classes; the browser app embeds it with kernelSource().
        for (name in listOf("risk.pricePaths", "risk.samplePaths")) {
            val wgsl = KernelinShaders.wgsl(name)
            assertTrue("@compute" in wgsl, wgsl)
            println("$name: ${wgsl.lines().size} lines of WGSL")
        }
    }
}
