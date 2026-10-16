package photo

import io.github.rajumark.kernelin.KernelinShaders
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The kernels as plain Kotlin on the CPU. The browser runs exactly these functions on the GPU. */
class PipelineTest {
    private val w = 600
    private val h = 400
    private val clean by lazy { CpuPipeline.chart(w, h) }
    private val noisy by lazy { CpuPipeline.noise(clean, 6400f, 1) }

    private fun channelDiff(a: Int, b: Int) =
        maxOf(abs(red(a) - red(b)), abs(green(a) - green(b)), abs(blue(a) - blue(b))) * 255f

    @Test
    fun `chart has the exact ColorChecker colors`() {
        // Middle of the first patch (dark skin, sRGB 115 82 68).
        val p = clean[(0.05f * h + 0.1f * h).toInt() * w + (0.03f * w + 0.04f * w).toInt()]
        assertEquals(115, p and 255)
        assertEquals(82, (p ushr 8) and 255)
        assertEquals(68, (p ushr 16) and 255)
        assertEquals(255, p ushr 24)
    }

    @Test
    fun `develop with neutral settings changes nothing`() {
        val out = CpuPipeline.develop(clean, w, h, DevelopSettings())
        val worst = clean.indices.maxOf { channelDiff(clean[it], out[it]) }
        assertTrue(worst <= 1f, "neutral develop moved a channel by $worst levels")
    }

    @Test
    fun `exposure plus one stop doubles linear light`() {
        val gray = IntArray(16) { pack(0.4f, 0.4f, 0.4f) }
        val out = CpuPipeline.develop(gray, 4, 4, DevelopSettings(exposure = 1f))
        assertEquals(toLinear(0.4f) * 2f, toLinear(red(out[5])), 0.01f)
    }

    @Test
    fun `white balance warms and cools`() {
        val gray = IntArray(16) { pack(0.5f, 0.5f, 0.5f) }
        val warm = CpuPipeline.develop(gray, 4, 4, DevelopSettings(temperature = 0.5f))[5]
        assertTrue(red(warm) > 0.5f && blue(warm) < 0.5f)
    }

    @Test
    fun `denoise removes noise and keeps the chart's edges`() {
        val denoised = CpuPipeline.denoise(noisy, w, h, DenoiseSettings())
        val before = CpuPipeline.psnr(noisy, clean, w, h)
        val after = CpuPipeline.psnr(denoised, clean, w, h)
        println("PSNR: noisy %.2f dB, denoised %.2f dB".format(before, after))
        // The default (radius 6, color sigma 0.2) was picked by a sweep over radius 3..7 and sigma 0.08..0.3.
        assertTrue(after > before + 9, "denoise should gain at least 9 dB")

        // Edge between a patch and the background: a box blur of the same size would smear it over 11 pixels.
        val row = (0.05f * h + 0.1f * h).toInt()
        val edge = (0.03f * w + 0.08f * 0.56f / 6f * w).toInt() + 1
        val inside = red(denoised[row * w + edge + 3])
        val outside = red(denoised[row * w + edge - 3])
        assertTrue(inside - outside > 0.15f, "edge contrast lost: $outside -> $inside")
    }

    @Test
    fun `sensor noise grows with ISO`() {
        val low = CpuPipeline.psnr(CpuPipeline.noise(clean, 400f, 1), clean, w, h)
        val high = CpuPipeline.psnr(noisy, clean, w, h)
        assertTrue(low > high + 8, "ISO 400 should be much cleaner than ISO 6400: $low vs $high dB")
    }

    @Test
    fun `batches give the same result as one run`() {
        val full = CpuPipeline.denoise(noisy, w, h, DenoiseSettings(radius = 2))
        val part = IntArray(noisy.size)
        val s = DenoiseSettings(radius = 2)
        io.github.rajumark.kernelin.KernelinCpu.dispatch(noisy.size / 2) { denoise(noisy, part, w, h, s.radius, s.spatial, s.range, s.strength, 0) }
        io.github.rajumark.kernelin.KernelinCpu.dispatch(noisy.size / 2) { denoise(noisy, part, w, h, s.radius, s.spatial, s.range, s.strength, noisy.size / 2) }
        assertTrue(full.contentEquals(part))
    }

    @Test
    fun `Kernelin generated GPU shaders`() {
        for (k in listOf("denoise", "develop", "rowError", "testChart", "addSensorNoise")) {
            val wgsl = KernelinShaders.wgsl("photo.$k")
            assertTrue("@compute" in wgsl)
            println("photo.$k: ${wgsl.lines().size} lines of WGSL")
        }
    }
}
