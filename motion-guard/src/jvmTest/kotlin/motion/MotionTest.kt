package motion

import io.github.rajumark.kernelin.KernelinShaders
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The kernels as plain Kotlin on the CPU. The browser runs exactly these functions on the GPU. */
class MotionTest {
    private val w = 1280
    private val h = 720

    @Test
    fun `a known shift is found exactly in every block`() {
        val motion = CpuMotion(640, 352)
        val prev = IntArray(640 * 352) { i -> val x = i % 640; val y = i / 640; texture(x, y) }
        // Content moves 5 px right and 3 px up: cur(x, y) = prev(x - 5, y + 3).
        val cur = IntArray(640 * 352) { i -> val x = i % 640; val y = i / 640; texture(x - 5, y + 3) }
        val field = analyzeBlocks(motion.match(prev, cur), 640, 352, DetectorSettings(), null)
        var checked = 0
        for (b in field.dx.indices) {
            val bx = b % field.blocksX
            val by = b / field.blocksX
            if (bx == 0 || by == field.blocksY - 1) continue // border blocks see clamped pixels
            assertEquals(5, field.dx[b], "block $b dx")
            assertEquals(-3, field.dy[b], "block $b dy")
            assertTrue(field.moving[b])
            checked++
        }
        assertTrue(checked > 700)
    }

    @Test
    fun `sensor noise alone is never motion`() {
        val motion = CpuMotion(w, h)
        // Same moment twice, different camera noise.
        val field = analyzeBlocks(motion.match(motion.scene(40, noiseSeed = 1), motion.scene(40, noiseSeed = 2)), w, h, DetectorSettings(), null)
        assertEquals(0, field.movingCount)
    }

    @Test
    fun `traffic scene - accuracy, tree, and lane speeds against ground truth`() {
        val motion = CpuMotion(w, h)
        val score = Score()
        val speeds = SCENE_LANES.associateWith { mutableListOf<Float>() }
        var prev = motion.scene(100)
        for (f in 101..130) {
            val cur = motion.scene(f)
            val field = motion.process(prev, cur)
            if (f > 102) score.add(field, w, h, f)
            SCENE_LANES.forEach { lane -> laneSpeed(field, lane, h)?.let { speeds.getValue(lane) += it } }
            prev = cur
        }
        println("precision %.3f, recall %.3f, vector error %.2f px, tree %.2f%%".format(score.precision, score.recall, score.vectorError, score.treeFalseAlarmRate * 100))
        assertTrue(score.precision > 0.97, "precision ${score.precision}")
        assertTrue(score.recall > 0.6, "recall ${score.recall}")
        assertTrue(score.vectorError < 0.8, "vector error ${score.vectorError}")
        assertEquals(0.0, score.treeFalseAlarmRate, 0.01, "swaying leaves must not count as motion")
        SCENE_LANES.forEachIndexed { i, lane ->
            val truth = objectVelocity(i + 1, w, h).first * 30f / (0.08f * h / 3.5f) * 3.6f
            val list = speeds.getValue(lane)
            if (list.isEmpty()) return@forEachIndexed // car outside the picture in these frames
            val measured = list.sorted()[list.size / 2]
            println("${lane.name}: %.1f km/h, true %.1f km/h".format(measured, truth))
            assertTrue(abs(measured - truth) < 0.12f * abs(truth), "${lane.name}: $measured vs $truth km/h")
        }
    }

    @Test
    fun `events need a few frames of motion and end after a quiet period`() {
        val zones = Zones(8, 5, cols = 8, rows = 5)
        val tracker = EventTracker(zones, confirmFrames = 3, quietFrames = 5)
        val still = MotionField(8, 5)
        val busy = MotionField(8, 5).apply { moving[12] = true }
        tracker.update(0, busy)
        tracker.update(1, still) // one noisy frame: no event
        assertEquals(0, tracker.events.size)
        for (f in 2..6) tracker.update(f, busy)
        assertEquals(1, tracker.events.size)
        zones.armed[zones.zoneOf(12)] = false // an ignored zone never alarms
        for (f in 7..20) tracker.update(f, busy)
        assertEquals(null, tracker.active)
        assertEquals(1, tracker.events.size)
    }

    @Test
    fun `Kernelin generated GPU shaders`() {
        for (k in listOf("matchBlocks", "renderScene")) {
            val wgsl = KernelinShaders.wgsl("motion.$k")
            assertTrue("@compute" in wgsl)
            println("motion.$k: ${wgsl.lines().size} lines of WGSL")
        }
    }

    private fun texture(x: Int, y: Int): Int {
        val v = (hash2(x / 3 + 1000, y / 3 + 1000) and 127) + 64
        return rgb(v.toFloat(), v.toFloat(), v.toFloat())
    }
}
