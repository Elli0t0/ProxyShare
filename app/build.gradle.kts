plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionMajor = (project.findProperty("VERSION_MAJOR") as? String)?.toInt() ?: 0
val versionMinor = (project.findProperty("VERSION_MINOR") as? String)?.toInt() ?: 0
val versionPatch = (project.findProperty("VERSION_PATCH") as? String)?.toInt() ?: 2
val appVersionName = "$versionMajor.$versionMinor.$versionPatch"
// versionCode comes from CI: -PVERSION_CODE=$GITHUB_RUN_NUMBER. Local builds use 1.
val appVersionCode = (project.findProperty("VERSION_CODE") as? String)?.toIntOrNull() ?: 1

android {
    namespace = "com.example.proxyshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.proxyshare"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // Persistent key for the APKs, so every later update installs
        // over the previous one. Values come from the workflow as env vars:
        // KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD, set
        // either from PROXYSHARE_* repo secrets, or from the one-time key
        // the workflow generates (uploaded as the proxyshare-signing-
        // keystore artifact) until those secrets exist. Never commit a
        // keystore or a password; this block only reads env vars.
        create("proxyshare") {
            val keystorePath = System.getenv("KEYSTORE_PATH")?.trim()
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")?.trim()
            val keyAliasVal = System.getenv("KEY_ALIAS")?.trim()
            val keyPasswordVal = System.getenv("KEY_PASSWORD")?.trim()
            if (!keystorePath.isNullOrEmpty() && !keystorePassword.isNullOrEmpty()
                && !keyAliasVal.isNullOrEmpty() && !keyPasswordVal.isNullOrEmpty()
            ) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasVal
                keyPassword = keyPasswordVal
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            val persistentKey = signingConfigs.getByName("proxyshare")
            if (persistentKey.storeFile != null) {
                signingConfig = persistentKey
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val persistentKey = signingConfigs.getByName("proxyshare")
            if (persistentKey.storeFile != null) {
                signingConfig = persistentKey
            }
        }
    }

    androidResources {
        localeFilters += listOf("en", "fa")
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xno-param-assertions")
        freeCompilerArgs.add("-Xno-call-assertions")
        freeCompilerArgs.add("-Xno-receiver-assertions")
    }
}

base {
    archivesName.set("ProxyShare-$appVersionName")
}

// Rust engine build (cargo-ndk). The engine is a Rust cdylib; these tasks
// build it into app/src/main/jniLibs before the Android build packages it.
// Debug builds arm64-v8a + x86_64 only, for speed; release builds all ABIs.
//
// --platform 26 (== minSdk): cargo-ndk defaults to platform 21, whose
// libandroid stub does NOT export android_setsocknetwork (API 23+). The
// linker then drops the libandroid DT_NEEDED and leaves an unresolvable
// undefined reference, so System.loadLibrary fails on the phone and the
// app crashes on open (0.1.6-0.1.7). Platform 26 makes the stub export
// the symbol, so the .so records the DT_NEEDED and loads.
//
// The cargo/cargo-ndk presence check runs inside the shell command itself:
// a doFirst { checkCargoToolchain() } call breaks the configuration cache,
// because the task action then references the build script object, which
// cannot be serialized ("cannot serialize Gradle script object references").
// A plain command line is just data, so it is cache-safe.
val cargoToolchainCheck = "command -v cargo >/dev/null 2>&1 || " +
    "{ echo 'cargo is not installed or not on PATH. Install the Rust toolchain from https://rustup.rs.' >&2; exit 1; }; " +
    "command -v cargo-ndk >/dev/null 2>&1 || " +
    "{ echo 'cargo-ndk is not installed or not on PATH. Install it with: cargo install cargo-ndk' >&2; exit 1; }; "

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    description = "Build the Rust engine (debug, arm64-v8a + x86_64) into jniLibs."
    workingDir = rootProject.projectDir.resolve("rust")
    commandLine(
        "sh", "-c",
        cargoToolchainCheck +
            "exec cargo ndk --platform 26 -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build"
    )
}

val cargoBuildRelease = tasks.register<Exec>("cargoBuildRelease") {
    description = "Build the Rust engine (release, all ABIs) into jniLibs."
    workingDir = rootProject.projectDir.resolve("rust")
    commandLine(
        "sh", "-c",
        cargoToolchainCheck +
            "exec cargo ndk --platform 26 -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release"
    )
}

// preDebugBuild / preReleaseBuild are registered by AGP only after the
// build script is evaluated, so tasks.named() throws at configuration time
// ("Task with name 'preDebugBuild' not found"). configureEach fires when
// the task is registered, whenever that happens.
tasks.configureEach {
    when (name) {
        "preDebugBuild" -> dependsOn(cargoBuild)
        "preReleaseBuild" -> dependsOn(cargoBuildRelease)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
