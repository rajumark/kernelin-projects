# Kernelin projects

Real-world GPU applications written in **plain Kotlin** with [Kernelin](https://github.com/rajumark/kernelin), the
compiler plugin that turns `@Kernel` functions into WebGPU compute shaders. Each project:

- uses Kernelin **0.1.0 from Maven Central**, like your project would (no local builds, no tricks),
- runs the **same Kotlin functions** on the GPU (browser, via WebGPU) and on the CPU (JVM tool + unit tests),
- **checks its own results** against exact formulas or ground truth, so you can trust the GPU numbers,
- reports **measured** CPU-vs-GPU timings on real hardware.

**Live:** https://rajumark.github.io/kernelin-projects/

| Project | What it does | Check against | GPU speedup (Apple M5) |
|---|---|---|---|
| [risk-engine](risk-engine) · simulation | Monte Carlo pricing + greeks of an options book: 6 products incl. Asian, barrier, lookback; 1M paths × 252 days × 4 scenarios | Black–Scholes and geometric-Asian closed forms (every run) | **46 ms** vs 2.4 s on 10 JVM cores (53×), vs 7 s in-browser CPU (218×) |
| [photo-studio](photo-studio) · image | Photo editor with live sliders: edge-preserving denoise (169 taps/pixel), exposure, white balance, tone, color, sharpening, vignette | PSNR vs a noise-free test chart: **+11.3 dB** | denoise 3.8 MP in **48 ms** vs 790 ms on 10 JVM cores (16×), vs 6.8 s in-browser CPU (119×) |
| [motion-guard](motion-guard) · video | Traffic/security camera analytics: block-matching motion estimation (576 M pixel comparisons per 720p frame), vehicle speed per lane, zones and events | Ground truth of a synthetic camera: precision 0.995, recall 0.972, speed error < 6% | **19 ms**/frame (live 30 fps) vs 201 ms on 10 JVM cores (10×), vs 992 ms in-browser CPU (53×) |

## Why this exists

Kernelin's claim is that you can write GPU code in Kotlin, test it like any Kotlin code, and run it on any GPU. These
projects are the proof: workloads people actually pay for (derivatives pricing, photo editing, video analytics), with
numbers you can reproduce and checks that would fail if the GPU got it wrong.

Things we found while building them, now fixed in Kernelin:

- A helper function named like a WGSL built-in (`uniform`, `storage`, `fract`, ...) produced a shader Chrome rejected.
  Found by risk-engine; fixed for Kernelin 0.2.0 (these projects rename the helper to work with 0.1.0).

Known limits of Kernelin 0.1.0 these projects work around:

- `GpuKernel.run()` reads back every buffer the kernel writes. Stateful video pipelines would benefit from keeping
  buffers on the GPU between runs.
- `kernelSource()` works only in the module that defines the kernel; tests read the shipped shader with `KernelinShaders`.

## Build and run

Each folder is an independent Gradle project (JDK 21; Gradle downloads it if needed):

```bash
cd risk-engine
./gradlew jvmTest          # the kernels as plain Kotlin on the CPU, checked against exact answers
./gradlew jvmRun           # command-line report on all CPU cores
./gradlew exportSite       # builds the browser app into ../docs/risk-engine
```

Serve `docs/` with any static server (`python3 -m http.server -d docs`) and open it in Chrome, Edge or Safari.

## License

Apache 2.0
