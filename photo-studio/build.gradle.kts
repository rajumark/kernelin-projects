import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.21"
    // Kernelin from Maven Central: compiles @Kernel functions to GPU code and adds the runtime libraries.
    id("io.github.rajumark.kernelin") version "0.1.0"
}

kotlin {
    jvmToolchain(21)
    jvm {
        mainRun {
            mainClass.set("photo.MainKt")
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("photo-studio")
        browser {
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "3g"
    testLogging { events("passed", "failed"); showStandardStreams = true }
}

// gradle exportSite: builds the web app into ../docs/photo-studio, which GitHub Pages serves.
tasks.register<Copy>("exportSite") {
    group = "distribution"
    description = "Builds the browser app into ../docs/photo-studio."
    dependsOn("compileProductionExecutableKotlinWasmJsOptimize")
    into(layout.projectDirectory.dir("../docs/photo-studio"))
    from(layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/optimized")) {
        include("*.mjs", "*.wasm")
        into("wasm")
    }
    from("web")
    from("src/commonMain/kotlin/photo/Kernels.kt")
}
