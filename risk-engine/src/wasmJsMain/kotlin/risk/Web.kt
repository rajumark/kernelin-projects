@file:OptIn(ExperimentalWasmJsInterop::class, DelicateCoroutinesApi::class)

package risk

import io.github.rajumark.kernelin.KernelinCpu
import io.github.rajumark.kernelin.kernelSource
import io.github.rajumark.kernelin.webgpu.GpuData
import io.github.rajumark.kernelin.webgpu.GpuKernel
import io.github.rajumark.kernelin.webgpu.WebGpu
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlin.js.Promise

// The functions web/index.html calls. The page only draws; every number comes from the Kotlin kernels,
// on the GPU through Kernelin + WebGPU, or on the CPU as the same Kotlin compiled to Wasm.

private var priceKernel: GpuKernel? = null
private var pathsKernel: GpuKernel? = null
private var cpuProgress = 0.0

/** Most GPUs allow 128 MB+ storage buffers; 1M threads x 13 floats = 52 MB keeps us well under on phones too. */
private const val GPU_BATCH_THREADS = 1 shl 20

private class GpuEngine(private val kernel: GpuKernel) : PathEngine {
    override suspend fun run(market: Market, threads: Int, seed: Int): FloatArray {
        val out = FloatArray(threads * OUTPUTS_PER_THREAD)
        var first = 0
        while (first < threads) {
            val count = minOf(GPU_BATCH_THREADS, threads - first)
            val result = kernel.run(
                size = count,
                scalars = market.scalars(seed, first),
                buffers = mapOf("out" to GpuData.empty(count * OUTPUTS_PER_THREAD)),
            )
            result.floats("out").copyInto(out, first * OUTPUTS_PER_THREAD)
            first += count
        }
        return out
    }
}

/** The same kernel on the CPU: one browser thread, in batches so the page stays responsive. */
private object WasmCpuEngine : PathEngine {
    var done = 0
    var total = 1

    override suspend fun run(market: Market, threads: Int, seed: Int): FloatArray {
        val out = FloatArray(threads * OUTPUTS_PER_THREAD)
        val m = market
        var first = 0
        while (first < threads) {
            val count = minOf(256, threads - first)
            val batch = FloatArray(count * OUTPUTS_PER_THREAD)
            val offset = first
            KernelinCpu.dispatch(count) {
                pricePaths(
                    batch, m.spot.toFloat(), m.strike.toFloat(), m.rate.toFloat(), m.vol.toFloat(),
                    m.maturity.toFloat(), m.barrier.toFloat(), m.steps, PATHS_PER_THREAD, seed, offset,
                )
            }
            batch.copyInto(out, first * OUTPUTS_PER_THREAD)
            first += count
            done += count
            cpuProgress = done.toDouble() / total
            delay(1)
        }
        return out
    }
}

private fun Market.scalars(seed: Int, threadOffset: Int): Map<String, Number> = mapOf(
    "spot" to spot.toFloat(), "strike" to strike.toFloat(), "rate" to rate.toFloat(), "vol" to vol.toFloat(),
    "maturity" to maturity.toFloat(), "barrier" to barrier.toFloat(), "steps" to steps,
    "pathsPerThread" to PATHS_PER_THREAD, "seed" to seed, "threadOffset" to threadOffset,
)

private fun market(spot: Double, strike: Double, rate: Double, vol: Double, maturity: Double, barrier: Double, steps: Int) =
    Market(spot, strike, rate, vol, maturity, barrier, steps)

/** Sets up WebGPU and both kernels; resolves to the GPU name. Rejects when WebGPU isn't available. */
@JsExport
fun riskInit(): Promise<JsString> = GlobalScope.promise {
    val gpu = WebGpu.create()
    priceKernel = gpu.load(kernelSource(::pricePaths))
    pathsKernel = gpu.load(kernelSource(::samplePaths))
    gpu.description.toJsString()
}

/** Full risk report (prices + greeks, 4 scenarios) on the GPU; resolves to JSON. */
@JsExport
fun riskGpu(spot: Double, strike: Double, rate: Double, vol: Double, maturity: Double, barrier: Double, steps: Int, paths: Int, seed: Int): Promise<JsString> =
    GlobalScope.promise {
        val engine = GpuEngine(priceKernel ?: error("call riskInit() first"))
        riskReport(engine, market(spot, strike, rate, vol, maturity, barrier, steps), paths, seed, "GPU (WebGPU)", ::now).toJson().toJsString()
    }

/** Price only (1 scenario) on the GPU, for the convergence chart; resolves to JSON {price, se, exact, millis}. */
@JsExport
fun priceGpu(spot: Double, strike: Double, rate: Double, vol: Double, maturity: Double, barrier: Double, steps: Int, paths: Int, seed: Int): Promise<JsString> =
    GlobalScope.promise {
        val engine = GpuEngine(priceKernel ?: error("call riskInit() first"))
        val m = market(spot, strike, rate, vol, maturity, barrier, steps)
        val threads = (paths + PATHS_PER_THREAD - 1) / PATHS_PER_THREAD
        val start = now()
        val prices = aggregate(engine.run(m, threads, seed), threads, m)
        val ms = now() - start
        val call = prices.products[0]
        "{\"price\":${call.price},\"se\":${call.stdError},\"exact\":${Analytic.call(m)},\"millis\":$ms}".toJsString()
    }

/** The same full risk report computed by the Kotlin kernel on the CPU (Wasm, 1 thread); resolves to JSON. */
@JsExport
fun riskCpu(spot: Double, strike: Double, rate: Double, vol: Double, maturity: Double, barrier: Double, steps: Int, paths: Int, seed: Int): Promise<JsString> =
    GlobalScope.promise {
        val threads = (paths + PATHS_PER_THREAD - 1) / PATHS_PER_THREAD
        WasmCpuEngine.done = 0
        WasmCpuEngine.total = threads * 4
        cpuProgress = 0.0
        riskReport(WasmCpuEngine, market(spot, strike, rate, vol, maturity, barrier, steps), paths, seed, "CPU (Wasm, 1 thread)", ::now).toJson().toJsString()
    }

/** Progress of the running [riskCpu] call, 0..1. */
@JsExport
fun riskCpuProgress(): Double = cpuProgress

/** [count] sample price paths on the GPU for the fan chart: a Float32Array of count x (steps + 1) prices. */
@JsExport
fun samplePathsGpu(spot: Double, rate: Double, vol: Double, maturity: Double, steps: Int, count: Int, seed: Int): Promise<JsAny> =
    GlobalScope.promise {
        val k = pathsKernel ?: error("call riskInit() first")
        val result = k.run(
            size = count,
            scalars = mapOf(
                "spot" to spot.toFloat(), "rate" to rate.toFloat(), "vol" to vol.toFloat(),
                "maturity" to maturity.toFloat(), "steps" to steps, "seed" to seed,
            ),
            buffers = mapOf("out" to GpuData.empty(count * (steps + 1))),
        )
        result.js("out")
    }

/** The Kotlin source of the kernels and the WGSL Kernelin generated, for the "see the code" panel. */
@JsExport
fun riskWgsl(): String = kernelSource(::pricePaths).wgsl

private fun now(): Double = js("performance.now()")
