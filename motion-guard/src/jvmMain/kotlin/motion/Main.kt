package motion

import io.github.rajumark.kernelin.KernelinCpu

/** Motion estimation on the JVM. The browser runs the same kernels on the GPU. */
class CpuMotion(val width: Int, val height: Int, val settings: DetectorSettings = DetectorSettings(), var parallel: Boolean = true) {
    private val raw = IntArray(3 * matchThreads(width, height, settings.range))
    private var previous: MotionField? = null

    private fun dispatch(size: Int, kernel: () -> Unit) =
        if (parallel) KernelinCpu.dispatchParallel(size, kernel) else KernelinCpu.dispatch(size, kernel)

    /** Raw block matching only (the part the GPU does). */
    fun match(prev: IntArray, cur: IntArray): IntArray {
        dispatch(matchThreads(width, height, settings.range)) {
            matchBlocks(prev, cur, raw, width, height, blocksX(width), settings.range)
        }
        return raw
    }

    fun process(prev: IntArray, cur: IntArray): MotionField =
        analyzeBlocks(match(prev, cur), width, height, settings, previous).also { previous = it }

    fun scene(index: Int, noise: Float = 3.5f, noiseSeed: Int = index): IntArray =
        IntArray(width * height).also { f -> dispatch(f.size) { renderScene(f, width, height, index, noise, noiseSeed) } }
}

/**
 * `gradle jvmRun`: 10 seconds (300 frames) of the synthetic 720p traffic camera through motion estimation on the CPU,
 * with speed and accuracy against ground truth.
 */
fun main() {
    val w = 1280
    val h = 720
    val cores = Runtime.getRuntime().availableProcessors()
    val s = DetectorSettings()
    val ops = blocksX(w).toLong() * blocksY(h) * (2 * s.range + 1) * (2 * s.range + 1) * BLOCK * BLOCK
    println("Synthetic traffic camera ${w}x$h at 30 fps, block matching ±${s.range} px: ${ops / 1_000_000} M pixel comparisons per frame")
    println("JVM ${System.getProperty("java.version")}, $cores cores")

    val single = CpuMotion(w, h, parallel = false)
    val a = single.scene(0)
    val b = single.scene(1)
    single.match(a, b)
    val t0 = System.nanoTime()
    single.match(a, b)
    println("1 core:   %7.0f ms per frame".format((System.nanoTime() - t0) / 1e6))

    val motion = CpuMotion(w, h)
    val zones = Zones(blocksX(w), blocksY(h))
    val tracker = EventTracker(zones)
    val score = Score()
    var prev = motion.scene(0)
    var nanos = 0L
    val frames = 300
    val speeds = SCENE_LANES.associateWith { mutableListOf<Float>() }
    for (f in 1 until frames) {
        val cur = motion.scene(f)
        val t = System.nanoTime()
        val field = motion.process(prev, cur)
        nanos += System.nanoTime() - t
        tracker.update(f, field)
        if (f >= 3) score.add(field, w, h, f)
        SCENE_LANES.forEach { lane -> laneSpeed(field, lane, h)?.let { speeds.getValue(lane) += it } }
        prev = cur
    }
    val ms = nanos / 1e6 / (frames - 1)
    println("$cores cores: %6.0f ms per frame = %.1f fps".format(ms, 1000 / ms))
    println("Accuracy vs ground truth: precision %.3f, recall %.3f, vector error %.2f px/frame".format(score.precision, score.recall, score.vectorError))
    println("Tree blocks flagged as moving: %.2f%%".format(score.treeFalseAlarmRate * 100))
    SCENE_LANES.forEachIndexed { i, lane ->
        val (vx, _) = objectVelocity(i + 1, w, h)
        val truth = vx * 30f / (0.08f * h / 3.5f) * 3.6f
        val measured = speeds.getValue(lane).let { l -> if (l.isEmpty()) Float.NaN else l.sorted()[l.size / 2] }
        println("${lane.name}: measured %.1f km/h (median), true %.1f km/h".format(measured, truth))
    }
    println("Events: ${tracker.events.size} ${tracker.events.take(5)}")
}
