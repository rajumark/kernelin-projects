@file:OptIn(ExperimentalWasmJsInterop::class, DelicateCoroutinesApi::class)

package photo

import io.github.rajumark.kernelin.KernelinCpu
import io.github.rajumark.kernelin.kernelSource
import io.github.rajumark.kernelin.webgpu.GpuData
import io.github.rajumark.kernelin.webgpu.GpuKernel
import io.github.rajumark.kernelin.webgpu.WebGpu
import io.github.rajumark.kernelin.webgpu.toJsInt32Array
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlin.js.Promise

// Functions web/index.html calls. The page draws pixels and handles sliders; all image math is the Kotlin kernels,
// on the GPU (Kernelin + WebGPU) or on the CPU (the same Kotlin compiled to Wasm).

private class Kernels(gpu: WebGpu) {
    val chart: suspend () -> GpuKernel = memo(gpu) { kernelSource(::testChart) }
    val noise: suspend () -> GpuKernel = memo(gpu) { kernelSource(::addSensorNoise) }
    val denoise: suspend () -> GpuKernel = memo(gpu) { kernelSource(::denoise) }
    val develop: suspend () -> GpuKernel = memo(gpu) { kernelSource(::develop) }
    val error: suspend () -> GpuKernel = memo(gpu) { kernelSource(::rowError) }
}

private fun memo(gpu: WebGpu, source: () -> io.github.rajumark.kernelin.KernelSource): suspend () -> GpuKernel {
    var k: GpuKernel? = null
    return { k ?: gpu.load(source()).also { k = it } }
}

private var kernels: Kernels? = null

// The loaded image. GPU kernels keep their input buffers between runs, so each image is uploaded once:
// later runs pass GpuData.empty() for unchanged inputs and only the sliders' numbers go to the GPU.
private var width = 0
private var height = 0
private var noisyJs: JsAny? = null
private var cleanJs: JsAny? = null
private var denoisedJs: JsAny? = null
private var noisyUploaded = false
private var cleanUploaded = false
private var noisyCpu: IntArray? = null
private var cleanCpu: IntArray? = null
private var cpuProgress = 0.0

@JsExport
fun photoInit(): Promise<JsString> = GlobalScope.promise {
    val gpu = WebGpu.create()
    kernels = Kernels(gpu)
    gpu.description.toJsString()
}

private fun setImage(noisy: JsAny, clean: JsAny?, w: Int, h: Int) {
    width = w
    height = h
    noisyJs = noisy
    cleanJs = clean
    denoisedJs = null
    noisyUploaded = false
    cleanUploaded = false
    noisyCpu = null
    cleanCpu = null
}

/** Draws the test chart and adds sensor noise on the GPU; resolves to `{ clean, noisy, psnr, ms }`. */
@JsExport
fun photoLoadChart(w: Int, h: Int, iso: Double, seed: Int): Promise<JsAny> = GlobalScope.promise {
    val k = kernels ?: error("call photoInit() first")
    val n = w * h
    val start = now()
    val clean = k.chart().run(n, mapOf("width" to w, "height" to h),
        mapOf("out" to GpuData.empty(n), "palette" to GpuData.of(COLOR_CHECKER))).js("out")
    val noisy = k.noise().run(n, mapOf("iso" to iso.toFloat(), "seed" to seed),
        mapOf("clean" to GpuData.ofJs(clean), "noisy" to GpuData.empty(n))).js("noisy")
    val ms = now() - start
    setImage(noisy, clean, w, h)
    chartResult(clean, noisy, gpuPsnr(noisy), ms)
}

/** The same chart on the CPU (Wasm), when the browser has no WebGPU. */
@JsExport
fun photoLoadChartCpu(w: Int, h: Int, iso: Double, seed: Int): JsAny {
    val n = w * h
    val clean = IntArray(n)
    KernelinCpu.dispatch(n) { testChart(clean, COLOR_CHECKER, w, h) }
    val noisy = IntArray(n)
    KernelinCpu.dispatch(n) { addSensorNoise(clean, noisy, iso.toFloat(), seed) }
    setImage(noisy.toJsInt32Array(), clean.toJsInt32Array(), w, h)
    noisyCpu = noisy
    cleanCpu = clean
    return chartResult(cleanJs!!, noisyJs!!, cpuPsnr(noisy), 0.0)
}

/** Uses a photo from the page (an Int32Array view of its ImageData); there's no clean reference for PSNR. */
@JsExport
fun photoLoadImage(pixels: JsAny, w: Int, h: Int) = setImage(pixels, null, w, h)

private suspend fun gpuPsnr(image: JsAny): Double {
    val clean = cleanJs ?: return -1.0
    val k = kernels!!.error()
    val rows = k.run(
        height, mapOf("width" to width),
        mapOf("a" to GpuData.ofJs(image), "b" to if (cleanUploaded) GpuData.empty(width * height) else GpuData.ofJs(clean), "out" to GpuData.empty(height)),
    ).floats("out")
    cleanUploaded = true
    return psnr(rows, width, height)
}

/**
 * Runs the pipeline on the GPU. Denoise only reruns when its settings changed ([redenoise]); develop always runs.
 * Resolves to `{ denoiseMs, developMs, psnr, data }` (psnr of the denoised image vs the clean chart, -1 for photos).
 */
@JsExport
fun photoProcess(
    redenoise: Boolean, radius: Int, colorSigma: Double, strength: Double,
    exposure: Double, contrast: Double, highlights: Double, shadows: Double,
    temperature: Double, tint: Double, saturation: Double, sharpen: Double, vignette: Double,
): Promise<JsAny> = GlobalScope.promise {
    val k = kernels ?: error("call photoInit() first")
    val n = width * height
    var denoiseMs = -1.0
    var quality = -1.0
    val fresh = redenoise || denoisedJs == null
    if (fresh) {
        val s = DenoiseSettings(radius, colorSigma.toFloat(), strength.toFloat())
        val start = now()
        denoisedJs = k.denoise().run(
            n,
            mapOf("width" to width, "height" to height, "radius" to s.radius, "spatial" to s.spatial, "range" to s.range,
                "strength" to s.strength, "first" to 0),
            mapOf("src" to if (noisyUploaded) GpuData.empty(n) else GpuData.ofJs(noisyJs!!), "dst" to GpuData.empty(n)),
        ).js("dst")
        noisyUploaded = true
        denoiseMs = now() - start
        quality = gpuPsnr(denoisedJs!!)
    }
    val start = now()
    val out = k.develop().run(
        n,
        mapOf("width" to width, "height" to height, "exposure" to exposure.toFloat(), "contrast" to contrast.toFloat(),
            "highlights" to highlights.toFloat(), "shadows" to shadows.toFloat(), "temperature" to temperature.toFloat(),
            "tint" to tint.toFloat(), "saturation" to saturation.toFloat(), "sharpen" to sharpen.toFloat(), "vignette" to vignette.toFloat()),
        mapOf("src" to if (fresh) GpuData.ofJs(denoisedJs!!) else GpuData.empty(n), "dst" to GpuData.empty(n)),
    ).js("dst")
    processResult(denoiseMs, now() - start, quality, out)
}

/** The same pipeline on the CPU: the Kotlin kernels compiled to Wasm, 1 thread, in batches with progress. */
@JsExport
fun photoProcessCpu(
    radius: Int, colorSigma: Double, strength: Double,
    exposure: Double, contrast: Double, highlights: Double, shadows: Double,
    temperature: Double, tint: Double, saturation: Double, sharpen: Double, vignette: Double,
): Promise<JsAny> = GlobalScope.promise {
    val w = width
    val h = height
    val n = w * h
    val src = noisyCpu ?: copyInts(noisyJs!!, n).also { noisyCpu = it }
    val s = DenoiseSettings(radius, colorSigma.toFloat(), strength.toFloat())
    val denoised = IntArray(n)
    cpuProgress = 0.0
    var cpuMs = 0.0
    val batch = w * 16
    var first = 0
    while (first < n) {
        val count = minOf(batch, n - first)
        val offset = first
        val t0 = now()
        KernelinCpu.dispatch(count) { denoise(src, denoised, w, h, s.radius, s.spatial, s.range, s.strength, offset) }
        cpuMs += now() - t0
        first += count
        cpuProgress = first.toDouble() / n
        delay(1)
    }
    val out = IntArray(n)
    val t0 = now()
    KernelinCpu.dispatch(n) {
        develop(denoised, out, w, h, exposure.toFloat(), contrast.toFloat(), highlights.toFloat(), shadows.toFloat(),
            temperature.toFloat(), tint.toFloat(), saturation.toFloat(), sharpen.toFloat(), vignette.toFloat())
    }
    val developMs = now() - t0
    processResult(cpuMs, developMs, if (cleanJs != null) cpuPsnr(denoised) else -1.0, out.toJsInt32Array())
}

@JsExport
fun photoCpuProgress(): Double = cpuProgress

private fun cpuPsnr(image: IntArray): Double {
    val clean = cleanCpu ?: copyInts(cleanJs!!, width * height).also { cleanCpu = it }
    val rows = FloatArray(height)
    val w = width
    KernelinCpu.dispatch(height) { rowError(image, clean, rows, w) }
    return psnr(rows, width, height)
}

/** The WGSL Kernelin generated for one kernel, for the code panel. */
@JsExport
fun photoWgsl(name: String): String = when (name) {
    "develop" -> kernelSource(::develop)
    "testChart" -> kernelSource(::testChart)
    "addSensorNoise" -> kernelSource(::addSensorNoise)
    else -> kernelSource(::denoise)
}.wgsl

private fun copyInts(array: JsAny, n: Int): IntArray = IntArray(n) { readInt(array, it) }

private fun readInt(array: JsAny, index: Int): Int = js("array[index]")

private fun chartResult(clean: JsAny, noisy: JsAny, psnr: Double, ms: Double): JsAny = js("({ clean, noisy, psnr, ms })")

private fun processResult(denoiseMs: Double, developMs: Double, psnr: Double, data: JsAny): JsAny =
    js("({ denoiseMs, developMs, psnr, data })")

private fun now(): Double = js("performance.now()")
