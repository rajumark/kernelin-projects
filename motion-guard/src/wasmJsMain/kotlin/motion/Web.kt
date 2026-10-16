@file:OptIn(ExperimentalWasmJsInterop::class, DelicateCoroutinesApi::class)

package motion

import io.github.rajumark.kernelin.KernelinCpu
import io.github.rajumark.kernelin.kernelSource
import io.github.rajumark.kernelin.webgpu.GpuData
import io.github.rajumark.kernelin.webgpu.GpuKernel
import io.github.rajumark.kernelin.webgpu.WebGpu
import io.github.rajumark.kernelin.webgpu.toJsInt32Array
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

// Functions web/index.html calls. The page grabs frames and draws; block matching runs on the GPU (Kernelin +
// WebGPU) or on the CPU (the same Kotlin compiled to Wasm), and all analysis (vectors, speeds, zones, events) is Kotlin.

private var matcher: GpuKernel? = null
private var renderer: GpuKernel? = null
private val settings = DetectorSettings()
private var width = 0
private var height = 0
private var previous: MotionField? = null
private var zones: Zones? = null
private var tracker: EventTracker? = null
private var score = Score()
private var frameCount = 0

@JsExport
fun motionInit(): Promise<JsString> = GlobalScope.promise {
    val gpu = WebGpu.create()
    matcher = gpu.load(kernelSource(::matchBlocks))
    renderer = gpu.load(kernelSource(::renderScene))
    gpu.description.toJsString()
}

/** Starts a new video of the given size: clears the motion history, zones and events. */
@JsExport
fun motionReset(w: Int, h: Int) {
    width = w
    height = h
    previous = null
    zones = Zones(blocksX(w), blocksY(h)).also { tracker = EventTracker(it) }
    score = Score()
    frameCount = 0
}

/** Arms or ignores zone [z] (8 x 5 grid, row by row). */
@JsExport
fun motionSetZone(z: Int, armed: Boolean) {
    zones?.armed?.set(z, armed)
}

/** Frame [index] of the synthetic traffic camera, rendered on the GPU; an Int32Array of pixels. */
@JsExport
fun sceneFrameGpu(index: Int): Promise<JsAny> = GlobalScope.promise {
    val k = renderer ?: error("call motionInit() first")
    k.run(width * height, mapOf("width" to width, "height" to height, "frameIndex" to index, "noise" to 3.5f, "noiseSeed" to index),
        mapOf("out" to GpuData.empty(width * height))).js("out")
}

/** The same frame rendered on the CPU (when there is no WebGPU). */
@JsExport
fun sceneFrameCpu(index: Int): JsAny {
    val out = IntArray(width * height)
    val w = width
    val h = height
    KernelinCpu.dispatch(out.size) { renderScene(out, w, h, index, 3.5f, index) }
    return out.toJsInt32Array()
}

/** Motion between two frames on the GPU; resolves to `{ ms, analyzeMs, field, stats }` (see [frameResult]). */
@JsExport
fun motionGpu(prev: JsAny, cur: JsAny, sceneIndex: Int): Promise<JsAny> = GlobalScope.promise {
    val k = matcher ?: error("call motionInit() first")
    val threads = matchThreads(width, height, settings.range)
    val result = k.run(
        threads,
        mapOf("width" to width, "height" to height, "blocksX" to blocksX(width), "range" to settings.range),
        mapOf("prev" to GpuData.ofJs(prev), "cur" to GpuData.ofJs(cur), "out" to GpuData.empty(3 * threads)),
    )
    frameResult(result.millis, result.ints("out"), sceneIndex)
}

/** The same frame on the CPU: the Kotlin kernel compiled to Wasm, 1 thread. Copies from JS are not timed. */
@JsExport
fun motionCpu(prev: JsAny, cur: JsAny, sceneIndex: Int): JsAny {
    val n = width * height
    val a = IntArray(n) { readInt(prev, it) }
    val b = IntArray(n) { readInt(cur, it) }
    val threads = matchThreads(width, height, settings.range)
    val raw = IntArray(3 * threads)
    val w = width
    val h = height
    val start = now()
    KernelinCpu.dispatch(threads) { matchBlocks(a, b, raw, w, h, blocksX(w), settings.range) }
    return frameResult(now() - start, raw, sceneIndex)
}

/**
 * Analyzes one frame's matches. `field` is an Int32Array with 3 ints per block (dx, dy, moving); `stats` is JSON with
 * lane speeds (synthetic scene only), zone activity, events and the running accuracy against ground truth.
 */
private fun frameResult(ms: Double, raw: IntArray, sceneIndex: Int): JsAny {
    val t0 = now()
    val field = analyzeBlocks(raw, width, height, settings, previous)
    previous = field
    frameCount++
    val events = tracker!!
    val armedMoving = events.update(frameCount, field)
    if (sceneIndex >= 0 && frameCount > 2) score.add(field, width, height, sceneIndex)
    val analyzeMs = now() - t0
    val packed = IntArray(field.dx.size * 3)
    for (b in field.dx.indices) {
        packed[3 * b] = field.dx[b]
        packed[3 * b + 1] = field.dy[b]
        packed[3 * b + 2] = if (field.moving[b]) 1 else 0
    }
    val stats = buildString {
        append("{\"moving\":").append(field.movingCount).append(",\"armedMoving\":").append(armedMoving)
        append(",\"blocksX\":").append(field.blocksX).append(",\"blocksY\":").append(field.blocksY)
        append(",\"lanes\":[")
        if (sceneIndex >= 0) SCENE_LANES.forEachIndexed { i, lane ->
            if (i > 0) append(',')
            val v = laneSpeed(field, lane, height)
            append("{\"name\":\"").append(lane.name).append("\",\"top\":").append(lane.top).append(",\"bottom\":").append(lane.bottom)
            append(",\"kmh\":").append(v?.toString() ?: "null").append('}')
        }
        append("],\"zones\":[").append(zones!!.fractions(field).joinToString(",")).append(']')
        append(",\"events\":[")
        events.events.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append("{\"start\":").append(e.startFrame).append(",\"end\":").append(e.endFrame).append(",\"peak\":").append(e.peakBlocks)
            append(",\"active\":").append(e === events.active).append('}')
        }
        append("],\"frame\":").append(frameCount)
        if (sceneIndex >= 0) {
            append(",\"precision\":").append(score.precision).append(",\"recall\":").append(score.recall)
            append(",\"vectorError\":").append(score.vectorError)
        }
        append('}')
    }
    return frame(ms, analyzeMs, packed.toJsInt32Array(), stats.toJsString())
}

/** The WGSL Kernelin generated for the block matcher, for the code panel. */
@JsExport
fun motionWgsl(): String = kernelSource(::matchBlocks).wgsl

private fun frame(ms: Double, analyzeMs: Double, field: JsAny, stats: JsString): JsAny = js("({ ms, analyzeMs, field, stats })")

private fun readInt(array: JsAny, index: Int): Int = js("array[index]")

private fun now(): Double = js("performance.now()")
