package motion

import kotlin.math.abs
import kotlin.math.sqrt

/** Analysis settings; the defaults suit a 720p camera at 30 fps. */
data class DetectorSettings(
    /** Search window: ±range pixels per frame (12 px at 30 fps is ~45 km/h across a 720p street view). */
    val range: Int = 12,
    /** A block moves only if its best match is clearly better than "no motion"... */
    val matchRatio: Float = 0.8f,
    /** ...and the block really changed: average difference per pixel above sensor noise (0..255 levels). */
    val minChange: Float = 6f,
    /** Smallest vector that counts, squared: 3 means at least 2 px/frame, which ignores swaying leaves. */
    val minMotion2: Int = 3,
)

/** Motion vector per block (px/frame) and whether the block counts as moving. */
class MotionField(val blocksX: Int, val blocksY: Int) {
    val dx = IntArray(blocksX * blocksY)
    val dy = IntArray(blocksX * blocksY)
    val moving = BooleanArray(blocksX * blocksY)
    /** Moving in this frame alone, before the two-frame consistency check. */
    val candidate = BooleanArray(blocksX * blocksY)
    val movingCount get() = moving.count { it }
}

fun blocksX(width: Int) = (width + BLOCK - 1) / BLOCK
fun blocksY(height: Int) = (height + BLOCK - 1) / BLOCK

/** GPU/CPU threads for [matchBlocks]: one per block and vertical offset. */
fun matchThreads(width: Int, height: Int, range: Int) = blocksX(width) * blocksY(height) * (2 * range + 1)

/**
 * Picks the best vector per block from [matchBlocks] output and decides which blocks move. A block must look like it moves in
 * this frame and (itself or a neighbour) in the previous one, in about the same direction, so single noisy matches never count.
 */
fun analyzeBlocks(raw: IntArray, width: Int, height: Int, s: DetectorSettings, previous: MotionField?): MotionField {
    val field = MotionField(blocksX(width), blocksY(height))
    val span = 2 * s.range + 1
    for (b in field.dx.indices) {
        var best = Int.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        var zero = 0
        for (row in 0 until span) {
            val t = b * span + row
            val dy = row - s.range
            val sad = raw[3 * t]
            if (dy == 0) zero = raw[3 * t + 2]
            if (sad < best || (sad == best && abs(dy) < abs(bestDy))) {
                best = sad
                bestDx = raw[3 * t + 1]
                bestDy = dy
            }
        }
        field.dx[b] = bestDx
        field.dy[b] = bestDy
        val changed = zero > s.minChange * BLOCK * BLOCK
        val clear = best < zero * s.matchRatio
        val big = bestDx * bestDx + bestDy * bestDy >= s.minMotion2
        val now = changed && clear && big
        field.candidate[b] = now
        val consistent = previous == null || neighborAgrees(previous, b, bestDx, bestDy)
        field.moving[b] = now && consistent
    }
    return field
}

/** The block or a neighbour (objects shift between blocks) was a candidate in the previous frame with a similar vector. */
private fun neighborAgrees(previous: MotionField, b: Int, dx: Int, dy: Int): Boolean {
    val bx = b % previous.blocksX
    val by = b / previous.blocksX
    for (ny in by - 1..by + 1) for (nx in bx - 1..bx + 1) {
        if (nx < 0 || ny < 0 || nx >= previous.blocksX || ny >= previous.blocksY) continue
        val n = ny * previous.blocksX + nx
        if (previous.candidate[n] && abs(previous.dx[n] - dx) <= 3 && abs(previous.dy[n] - dy) <= 3) return true
    }
    return false
}

/** A traffic lane of the synthetic scene: rows [top, bottom) as fractions of the height, and its direction. */
data class Lane(val name: String, val top: Float, val bottom: Float)

val SCENE_LANES = listOf(Lane("Lane 1", 0.63f, 0.71f), Lane("Lane 2", 0.71f, 0.79f), Lane("Lane 3", 0.85f, 0.93f))

/**
 * Average speed of moving blocks in a lane, in km/h (negative = right to left), or null when nothing moves there.
 * [pixelsPerMeter] calibrates the camera: here a 3.5 m lane is 8% of the picture height.
 */
fun laneSpeed(field: MotionField, lane: Lane, height: Int, fps: Float = 30f, pixelsPerMeter: Float = 0.08f * height / 3.5f): Float? {
    var sum = 0
    var n = 0
    for (by in 0 until field.blocksY) {
        // A block belongs to the lane its center is in.
        val center = (by * BLOCK + BLOCK / 2f) / height
        if (center < lane.top || center >= lane.bottom) continue
        for (bx in 0 until field.blocksX) {
            val b = by * field.blocksX + bx
            if (!field.moving[b]) continue
            sum += field.dx[b]
            n++
        }
    }
    if (n < 3) return null
    return sum.toFloat() / n * fps / pixelsPerMeter * 3.6f
}

/** Zones of blocks that can be armed or ignored (a tree, the road outside the fence). */
class Zones(val blocksX: Int, val blocksY: Int, val cols: Int = 8, val rows: Int = 5) {
    val armed = BooleanArray(cols * rows) { true }

    fun zoneOf(b: Int): Int {
        val zx = (b % blocksX) * cols / blocksX
        val zy = (b / blocksX) * rows / blocksY
        return zy * cols + zx
    }

    /** Fraction of moving blocks per zone. */
    fun fractions(field: MotionField): FloatArray {
        val moving = IntArray(cols * rows)
        val total = IntArray(cols * rows)
        for (b in field.moving.indices) {
            val z = zoneOf(b)
            total[z]++
            if (field.moving[b]) moving[z]++
        }
        return FloatArray(cols * rows) { moving[it].toFloat() / total[it].coerceAtLeast(1) }
    }
}

/** One motion event, like a recorder's event list. */
data class MotionEvent(val startFrame: Int, var endFrame: Int, var peakBlocks: Int)

/**
 * Turns per-frame activity into events: an event starts when armed zones have moving blocks for [confirmFrames]
 * frames in a row and ends after [quietFrames] calm frames.
 */
class EventTracker(private val zones: Zones, private val confirmFrames: Int = 3, private val quietFrames: Int = 30) {
    val events = mutableListOf<MotionEvent>()
    var active: MotionEvent? = null
        private set
    private var hot = 0
    private var calm = 0

    /** Feeds one frame; returns the number of moving blocks in armed zones. */
    fun update(frame: Int, field: MotionField): Int {
        var count = 0
        for (b in field.moving.indices) if (field.moving[b] && zones.armed[zones.zoneOf(b)]) count++
        if (count > 0) {
            hot++
            calm = 0
            if (active == null && hot >= confirmFrames) active = MotionEvent(frame - confirmFrames + 1, frame, count).also { events += it }
        } else {
            hot = 0
            calm++
            if (active != null && calm >= quietFrames) active = null
        }
        active?.let {
            it.endFrame = frame
            it.peakBlocks = maxOf(it.peakBlocks, count)
        }
        return count
    }
}

/** True velocity of a scene object in px/frame at 30 fps (from the motion formulas in [carLeft], [walkerU], [walkerV]). */
fun objectVelocity(id: Int, width: Int, height: Int): Pair<Float, Float> = when (id) {
    1 -> 0.09f * 1.5f * width / 30f to 0f
    2 -> -0.06f * 1.5f * width / 30f to 0f
    3 -> 0.13f * 1.5f * width / 30f to 0f
    4 -> 0.5f * 0.1f * width / 30f to 0.12f * 0.1f * height / 30f
    else -> -0.4f * 0.12f * width / 30f to 0.06f * 0.12f * height / 30f
}

/** Block-level accuracy against the synthetic scene's ground truth. */
class Score {
    var truePositive = 0L
    var falsePositive = 0L
    var falseNegative = 0L
    var treeBlocks = 0L
    var treeMoving = 0L
    private var errorSum = 0.0

    /** Scores frame [index]: a block is truly moving when an object covers at least half of it. */
    fun add(field: MotionField, width: Int, height: Int, index: Int) {
        val time = index / 30f
        for (b in field.moving.indices) {
            val bx = (b % field.blocksX) * BLOCK
            val by = (b / field.blocksX) * BLOCK
            val votes = IntArray(6)
            for (j in 0 until BLOCK step 2) for (k in 0 until BLOCK step 2) {
                if (bx + k < width && by + j < height) votes[objectAt(bx + k, by + j, width, height, time)]++
            }
            val id = (1..5).maxBy { votes[it] }
            val covered = votes[id] >= 32
            val cx = bx + 8 - 0.1f * width
            val cy = by + 8 - 0.43f * height
            val inTree = cx * cx + cy * cy < (0.13f * height + 12f) * (0.13f * height + 12f)
            val detected = field.moving[b]
            when {
                covered -> if (detected) {
                    truePositive++
                    val (vx, vy) = objectVelocity(id, width, height)
                    val ex = field.dx[b] - vx
                    val ey = field.dy[b] - vy
                    errorSum += sqrt(ex * ex + ey * ey)
                } else falseNegative++
                inTree -> { treeBlocks++; if (detected) treeMoving++ }
                votes[id] == 0 && detected -> falsePositive++
            }
        }
    }

    val precision get() = truePositive.toDouble() / (truePositive + falsePositive).coerceAtLeast(1)
    val recall get() = truePositive.toDouble() / (truePositive + falseNegative).coerceAtLeast(1)
    /** Mean distance between detected and true motion vectors, px/frame. */
    val vectorError get() = errorSum / truePositive.coerceAtLeast(1)
    val treeFalseAlarmRate get() = treeMoving.toDouble() / treeBlocks.coerceAtLeast(1)
}
