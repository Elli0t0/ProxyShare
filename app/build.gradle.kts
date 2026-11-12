plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionMajor = (project.findProperty("VERSION_MAJOR") as? String)?.toInt() ?: 0
val versionMinor = (project.findProperty("VERSION_MINOR") as? String)?.toInt() ?: 1
val versionPatch = (project.findProperty("VERSION_PATCH") as? String)?.toInt() ?: 0
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
        // Persistent key for the debug APK, so every later update installs
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
            isMinifyEnabled = false
            isShrinkResources = false
        }
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

base {
    archivesName.set("ProxyShare-$appVersionName")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
}
