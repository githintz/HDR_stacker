plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// AddressSanitizer support for the `asan` build type (see buildTypes below).
// The instrumented libhdrstacker.so declares the NDK's shared ASan runtime as
// a library dependency, so the runtime must travel inside the APK for the
// linker to resolve when the decoder loads; this task stages it into a
// jniLibs directory keyed by ABI. (No wrap.sh: process start is normal, and
// ASan initialises lazily on first decode — see PhotoStudioApp.)
// ---------------------------------------------------------------------------
val asanRuntimeDir = layout.buildDirectory.dir("asanRuntime")

val packageAsanRuntime = tasks.register("packageAsanRuntime") {
    description = "Stages the NDK's ASan runtime libraries for the asan variant"
    val ndkDir = provider { android.ndkDirectory }
    outputs.dir(asanRuntimeDir)
    outputs.upToDateWhen { false }
    doLast {
        val outRoot = asanRuntimeDir.get().asFile
        outRoot.deleteRecursively()
        val toolchains = File(ndkDir.get(), "toolchains/llvm/prebuilt")
        // NDK versions differ in runtime layout: legacy
        // lib/clang/*/lib/linux/libclang_rt.asan-<arch>-android.so vs the
        // per-target <triple>/libclang_rt.asan.so. Match both, and stage each
        // runtime under BOTH spellings so whichever name the linker recorded
        // as DT_NEEDED in libhdrstacker.so resolves on-device.
        // Classify strictly by the arch embedded in the FILE NAME — the full
        // path always contains "linux-x86_64" (the host prebuilt dir), which
        // previously mis-bucketed the arm and riscv64 runtimes into x86_64.
        val runtimeName = Regex("""libclang_rt\.asan-(aarch64|arm|x86_64)-android\.so""")
        val found = toolchains.walkTopDown()
            .filter { it.isFile && runtimeName.matches(it.name) }
            .toList()
        if (found.isEmpty()) {
            throw GradleException("No ASan runtime libraries found under $toolchains")
        }
        for (lib in found) {
            val arch = runtimeName.matchEntire(lib.name)!!.groupValues[1]
            val abi = when (arch) {
                "aarch64" -> "arm64-v8a"
                "arm" -> "armeabi-v7a"
                else -> "x86_64"
            }
            val abiDir = File(outRoot, abi).apply { mkdirs() }
            lib.copyTo(File(abiDir, "libclang_rt.asan-$arch-android.so"), overwrite = true)
            lib.copyTo(File(abiDir, "libclang_rt.asan.so"), overwrite = true)
        }
        val staged = outRoot.walkTopDown().filter { it.isFile }
            .joinToString { it.relativeTo(outRoot).path }
        logger.lifecycle("Staged ASan runtimes: $staged")
    }
}

tasks.configureEach {
    if (name == "mergeAsanJniLibFolders") dependsOn(packageAsanRuntime)
}

android {
    namespace = "com.hdrstacker"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.hdrstacker"
        minSdk = 29          // Android 10 — scoped-storage gallery saves need no runtime permission
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // NEF decoding + OpenCV are native. Ship the common phone ABIs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // LibRaw builds without demosaic-pack / GPL extras by default here.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Debugging variant with LibRaw + the JNI bridge built under
        // AddressSanitizer, installable alongside the normal app. Used to
        // pinpoint the NEF-decode heap overrun on-device with the real files.
        // The panorama module is not built in this variant.
        create("asan") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".asan"
            versionNameSuffix = "-asan"
            signingConfig = signingConfigs.getByName("debug")
            externalNativeBuild {
                cmake {
                    arguments += "-DENABLE_ASAN=ON"
                }
            }
        }
    }

    sourceSets {
        getByName("asan") {
            jniLibs.srcDir(asanRuntimeDir)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // Exposes the OpenCV AAR's prebuilt native library + headers to CMake,
        // so the panorama module can link the stitching sources against it.
        prefab = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Extract native libs to disk on install, so the asan variant's
            // ASan runtime dependency resolves from the app's lib directory.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // OpenCV — official native build published to Maven Central since 4.9.0.
    // Provides HDR merge (Mertens/Debevec), AlignMTB and tonemapping used by the
    // native pipeline via its bundled libopencv_java4.so.
    implementation("org.opencv:opencv:4.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
