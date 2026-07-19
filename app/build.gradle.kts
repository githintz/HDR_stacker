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

val packageAsanRuntime = tasks.register<Copy>("packageAsanRuntime") {
    description = "Stages the NDK's ASan runtime libraries for the asan variant"
    from(provider {
        fileTree("${android.ndkDirectory}/toolchains/llvm/prebuilt") {
            include("**/libclang_rt.asan-*-android.so")
        }
    })
    into(asanRuntimeDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
    eachFile {
        val abi = when {
            name.contains("aarch64") -> "arm64-v8a"
            name.contains("-arm-") -> "armeabi-v7a"
            name.contains("x86_64") -> "x86_64"
            else -> null
        }
        if (abi == null) exclude() else path = "$abi/$name"
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
