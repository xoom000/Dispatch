import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    // Build will fail until google-services.json is placed in app/.
    // Get it from Firebase Console > Project Settings > Add Android App > download google-services.json
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.detekt)
}

// Load local.properties for secrets (FB_API_KEY, etc.)
val localPropsFile = rootProject.file("local.properties")
val fbApiKey: String = if (localPropsFile.exists()) {
    val props = Properties()
    localPropsFile.reader().use { props.load(it) }
    props.getProperty("fb.api.key", "")
} else ""

android {
    namespace = "dev.digitalgnosis.dispatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.digitalgnosis.dispatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.8"

        // File Bridge API key — loaded from local.properties (gitignored)
        buildConfigField("String", "FB_API_KEY", "\"${fbApiKey}\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Automatic 'Self-Update' Staging
// Copies the fresh APK to the File Bridge public directory after every debug build.
tasks.register<Copy>("stageUpdate") {
    from("build/outputs/apk/debug/app-debug.apk")
    into("/home/xoom000/.dispatch/files")
    rename { "dispatch-latest.apk" }
}

afterEvaluate {
    tasks.findByName("assembleDebug")?.finalizedBy("stageUpdate")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.firebase.crashlytics.ktx)

    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.lifecycle.process)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.chucker.library)
    releaseImplementation(libs.chucker.library.no.op)
    debugImplementation(libs.leakcanary.android)

    // Static analysis — Compose-specific lint rules (Slack)
    lintChecks(libs.compose.lint.checks)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Priority 1 — Security: encrypted token storage
    implementation(libs.security.crypto.ktx)

    // Priority 2 — Reliability: guaranteed sync + Hilt worker support
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)

    // Priority 3 — Modernization: type-safe nav + async preferences
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)

    // Priority 4 — Capabilities: image loading + adaptive responsive UI
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.adaptive.navigation)

    // Priority 5 — Performance: AOT compilation of hot paths
    implementation(libs.profileinstaller)

    // AppFunctions — expose Dispatch functions to AI agents
    implementation(libs.appfunctions)
    implementation(libs.appfunctions.service)
    ksp(libs.appfunctions.compiler)

    // Sherpa-ONNX — on-device neural TTS (Kokoro voices)
    implementation(files("libs/sherpa-onnx-1.12.28.aar"))

    // ── Test dependencies ──
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.arch.core.testing)
}

// ── Detekt — Kotlin static analysis ──────────────────────────────────────────
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    baseline = file("${rootProject.projectDir}/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        html.outputLocation.set(file("build/reports/detekt/detekt.html"))
        sarif.required.set(false)
        md.required.set(true)
        md.outputLocation.set(file("build/reports/detekt/detekt.md"))
    }
}
