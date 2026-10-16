package photo

import io.github.rajumark.kernelin.KernelinCpu
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The pipeline on the JVM, on all cores. The browser runs the same kernels on the GPU. */
object CpuPipeline {
    var parallel = true

    private fun dispatch(size: Int, kernel: () -> Unit) =
        if (parallel) KernelinCpu.dispatchParallel(size, kernel) else KernelinCpu.dispatch(size, kernel)

    fun chart(width: Int, height: Int): IntArray =
        IntArray(width * height).also { out -> dispatch(out.size) { testChart(out, COLOR_CHECKER, width, height) } }

    fun noise(clean: IntArray, iso: Float, seed: Int): IntArray =
        IntArray(clean.size).also { out -> dispatch(out.size) { addSensorNoise(clean, out, iso, seed) } }

    fun denoise(src: IntArray, width: Int, height: Int, s: DenoiseSettings): IntArray =
        IntArray(src.size).also { dst ->
            dispatch(src.size) { denoise(src, dst, width, height, s.radius, s.spatial, s.range, s.strength, 0) }
        }

    fun develop(src: IntArray, width: Int, height: Int, d: DevelopSettings): IntArray =
        IntArray(src.size).also { dst ->
            dispatch(src.size) {
                develop(src, dst, width, height, d.exposure, d.contrast, d.highlights, d.shadows, d.temperature, d.tint,
                    d.saturation, d.sharpen, d.vignette)
            }
        }

    fun psnr(a: IntArray, b: IntArray, width: Int, height: Int): Double {
        val rows = FloatArray(height)
        dispatch(height) { rowError(a, b, rows, width) }
        return psnr(rows, width, height)
    }
}

/** Reads a PNG/JPEG into the pipeline's pixel layout (red in the low byte). */
fun readImage(file: File): Triple<IntArray, Int, Int> {
    val img = ImageIO.read(file) ?: error("Can't read $file")
    val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    return Triple(IntArray(argb.size) { i -> fromArgb(argb[i]) }, img.width, img.height)
}

fun writePng(pixels: IntArray, width: Int, height: Int, file: File) {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, width, height, IntArray(pixels.size) { i -> fromArgb(pixels[i]) }, 0, width)
    ImageIO.write(img, "png", file)
}

/** ARGB (Java) <-> RGBA-in-little-endian (browser) swap red and blue; the same swap works both ways. */
private fun fromArgb(p: Int): Int = (p and 0xFF00FF00.toInt()) or ((p shr 16) and 255) or ((p and 255) shl 16)

/**
 * Batch tool: `gradle jvmRun --args="photo.jpg out.png"` denoises + develops a photo on all CPU cores.
 * Without arguments it runs the test-chart benchmark: ISO 6400 noise, denoise, and PSNR before/after.
 */
fun main(args: Array<String>) {
    val cores = Runtime.getRuntime().availableProcessors()
    val denoise = DenoiseSettings()
    val look = DevelopSettings(exposure = 0.3f, contrast = 0.15f, shadows = 0.3f, saturation = 0.15f, sharpen = 0.6f, vignette = -0.3f)
    if (args.size >= 2) {
        val (pixels, w, h) = readImage(File(args[0]))
        val t0 = System.nanoTime()
        val out = CpuPipeline.develop(CpuPipeline.denoise(pixels, w, h, denoise), w, h, look)
        println("Processed ${w}x$h on $cores cores in ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms")
        writePng(out, w, h, File(args[1]))
        return
    }
    val w = 2400
    val h = 1600
    println("Test chart ${w}x$h (${"%.1f".format(w * h / 1e6)} MP), ISO 6400 sensor noise, JVM ${System.getProperty("java.version")}, $cores cores")
    val clean = CpuPipeline.chart(w, h)
    val noisy = CpuPipeline.noise(clean, 6400f, 1)
    for (parallel in listOf(false, true)) {
        CpuPipeline.parallel = parallel
        CpuPipeline.denoise(noisy, w, h, denoise) // warm-up
        val t0 = System.nanoTime()
        val denoised = CpuPipeline.denoise(noisy, w, h, denoise)
        val t1 = System.nanoTime()
        CpuPipeline.develop(denoised, w, h, look)
        val t2 = System.nanoTime()
        println("%-10s denoise (r=%d, %d taps/pixel): %7.0f ms   develop: %5.0f ms".format(
            if (parallel) "$cores cores" else "1 core", denoise.radius, (2 * denoise.radius + 1).let { it * it },
            (t1 - t0) / 1e6, (t2 - t1) / 1e6,
        ))
        if (parallel) {
            println("PSNR vs clean chart: noisy %.2f dB -> denoised %.2f dB".format(
                CpuPipeline.psnr(noisy, clean, w, h), CpuPipeline.psnr(denoised, clean, w, h),
            ))
            File("build/chart").mkdirs()
            writePng(clean, w, h, File("build/chart/clean.png"))
            writePng(noisy, w, h, File("build/chart/noisy.png"))
            writePng(denoised, w, h, File("build/chart/denoised.png"))
            println("Wrote build/chart/{clean,noisy,denoised}.png")
        }
    }
}
