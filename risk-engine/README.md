# Risk Engine: Monte Carlo options pricing on the GPU, in plain Kotlin

**Live:** https://rajumark.github.io/kernelin-projects/risk-engine/

Prices a small options book and its greeks by Monte Carlo simulation, the standard method for path-dependent
derivatives at banks and trading firms:

| Product | Why Monte Carlo |
|---|---|
| European call and put | has an exact formula: used to **check** the engine |
| Asian call, geometric average | has an exact formula: second **check**, and the control variate |
| Asian call, arithmetic average | no formula |
| Up-and-out barrier call (daily monitoring) | no formula for discrete monitoring |
| Floating-strike lookback call | path dependent |

Each report simulates 4 scenarios on the same random numbers: base, spot +1%, spot −1%, vol +1 point. That gives
Delta, Gamma and Vega for every product (bump-and-reprice, as risk systems do it).

## The code

The whole numerical engine is [`Kernels.kt`](src/commonMain/kotlin/risk/Kernels.kt): one `@Kernel` function where each
GPU thread simulates 32 full 252-day paths. Random numbers are counter based (path, draw) → number, so CPU and GPU
use identical random numbers and no two paths share a stream. Kernelin turns it into a 134-line WGSL shader.

- `src/commonMain`: kernels, exact formulas, aggregation, greeks, control variate
- `src/jvmMain`: command-line report on all CPU cores
- `src/jvmTest`: 9 tests (textbook values, Black–Scholes match, geometric-Asian match, put-call parity, ordering, greeks, RNG, batching)
- `src/wasmJsMain` + `web/`: the browser app (GPU through WebGPU, CPU through the same Kotlin compiled to Wasm)

## Results

Default market: spot 100, strike 100, vol 25%, rate 3%, 1 year, barrier 140, 252 days, 1,048,576 paths.

| Product | Monte Carlo (GPU) | ± 95% | Exact |
|---|---|---|---|
| European call | 11.3628 | 0.0343 | 11.3485 (0.8 σ) |
| European put | 8.4073 | 0.0222 | 8.3930 (1.3 σ) |
| Asian call (geometric) | 6.1257 | 0.0179 | 6.1209 (0.5 σ) |
| Asian call (arithmetic, control variate) | 6.4205 | 0.0006 | – (879× less variance than plain MC) |

Greeks match the exact European values (delta 0.597 vs 0.597, gamma 0.0154 vs 0.0155).

**Speed, full risk report (4 × 1,048,576 paths × 252 days = 1.06 billion simulated price steps):**

| Where | Time |
|---|---|
| GPU, Apple M5, WebGPU in Chrome | **46 ms** (23 billion steps/s) |
| CPU, same Kotlin on the JVM, 10 cores | 2,445 ms |
| CPU, same Kotlin compiled to Wasm, 1 browser thread (262k paths) | 6.97 s vs 32 ms on the GPU (**218×**) |

GPU and CPU prices agree within 0.085 standard errors.

## Run it

```bash
./gradlew jvmTest
./gradlew jvmRun --args="1048576"
./gradlew exportSite   # then serve ../docs
```
