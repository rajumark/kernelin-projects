# Motion Guard: traffic and security camera analytics in plain Kotlin

> Status: the engine, JVM tool and tests are done; the browser (GPU) app is being built.

Finds where every 16×16 block of a video frame came from in the previous frame (block matching, the core of every
video encoder), which gives a motion vector per block. From the vectors: what moves, which way and how fast, vehicle
speed per lane in km/h, motion zones that can be armed or ignored, and an event log.

At 720p with a ±12 px search, that is **576 million pixel comparisons per frame**: exactly the kind of work a CPU
can't do at 30 fps and a GPU can.

## Checked against ground truth

The built-in camera is a synthetic parking lot rendered by a Kotlin kernel: building, textured asphalt, a tree whose
leaves sway (motion that must **not** alarm), slowly changing daylight, sensor noise, three cars and two pedestrians.
Every moving object's position and velocity is known exactly, so accuracy is measured, over 300 frames (10 s):

| Metric | Result |
|---|---|
| Precision (moving blocks that really move) | 0.995 |
| Recall (moving objects' blocks found) | 0.972 |
| Motion vector error | 0.36 px/frame |
| Swaying-tree blocks flagged as motion | 0% |
| Sensor noise alone flagged | 0 blocks |
| Lane speeds | 39.4 / −26.3 / 52.5 km/h measured vs 37.8 / −25.2 / 54.6 true |

## Speed (CPU, same Kotlin)

| Where | Per 720p frame |
|---|---|
| JVM, 1 core | 913 ms |
| JVM, 10 cores | 201 ms (5 fps) |

## The code

- [`Kernels.kt`](src/commonMain/kotlin/motion/Kernels.kt): `matchBlocks` (exhaustive search) and `renderScene` (the test camera)
- [`Detector.kt`](src/commonMain/kotlin/motion/Detector.kt): best vector per block, two-frame consistency, lane speed, zones, events, scoring
- `src/jvmTest`: 5 tests (an exact known shift, noise-only frames, scene accuracy and speeds, events, shaders)

```bash
./gradlew jvmTest
./gradlew jvmRun
```
