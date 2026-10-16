# Photo Studio: a GPU photo editor in plain Kotlin

**Live:** https://rajumark.github.io/kernelin-projects/photo-studio/

A photo editor where every slider is live: edge-preserving denoise, then exposure, white balance (temperature,
tint), shadows and highlights, contrast, saturation, unsharp-mask sharpening and vignette. Open your own photo (it
never leaves your device) or use the built-in test chart.

## Measured, not eyeballed

The test chart is the kind imaging labs use: the 24 ColorChecker patches (exact sRGB values), a 36-spoke Siemens
star, a zone plate up to the pixel limit and gray ramps. It is drawn on the GPU, then **realistic sensor noise** is
added (photon shot noise + read noise per channel, like a small sensor at ISO 6400). Because the clean original is
known, the app shows how much the denoise recovers in PSNR:

| | PSNR vs clean chart |
|---|---|
| ISO 6400 noisy | 23.7 dB |
| after denoise (radius 6, color tolerance 0.2) | 35.0 dB (**+11.3 dB**) |

The defaults were picked by a sweep over radius 3–7 and tolerance 0.08–0.3; a unit test checks edges stay sharp.

## The code

[`Kernels.kt`](src/commonMain/kotlin/photo/Kernels.kt): five `@Kernel` functions (`denoise`, `develop`, `rowError`,
`testChart`, `addSensorNoise`). The browser app uploads an image once; moving a develop slider only sends the slider
values and reruns one pass.

- `src/jvmMain`: batch tool, `./gradlew jvmRun --args="in.jpg out.png"` processes a photo on all cores
- `src/jvmTest`: 8 tests (exact chart colors, neutral develop is identity, +1 EV doubles light, white balance, denoise gain and edge sharpness, ISO scaling, batching)

## Speed (3.8 MP chart, denoise = 169 neighbours per pixel = 650 M weighted samples)

| Where | Denoise | Develop |
|---|---|---|
| GPU, Apple M5, WebGPU in Chrome | **48 ms** | **9 ms** per slider move |
| CPU, same Kotlin on the JVM, 10 cores | 790 ms | 126 ms |
| CPU, same Kotlin on the JVM, 1 core | 3,800 ms | 660 ms |
| CPU, same Kotlin as Wasm, 1 browser thread | 5.86 s | 939 ms |

GPU vs in-browser CPU: **119×**, with the same PSNR (34.95 dB) on both.
