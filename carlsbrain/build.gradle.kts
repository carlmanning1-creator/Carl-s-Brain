import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Load keystore credentials from local.properties (never committed to git).
// If the file or keys are absent the signing config is simply skipped,
// which means debug builds continue to work without any extra setup.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

ksp {
    // Emit a JSON schema per database version so migrations can be machine-verified
    // by MigrationTest. Generated at build time -- commit carlsbrain/schemas/*.json.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.carlmanning.carlsbrain"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carlmanning.carlsbrain"
        minSdk = 28
        targetSdk = 36
        versionCode = 25
        versionName = "2.11.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // Ship the exported schemas as androidTest assets so MigrationTestHelper can load them.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProps.getProperty("storeFile")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Originally required until Porcupine shipped a 16 KB-aligned .so. Porcupine is
            // gone (replaced by sherpa-onnx), so this may now be removable — but it also
            // affects every other native lib in the APK, so it is being left in place
            // deliberately rather than removed as a side effect of the wake-word swap.
            // Causes native libs to be extracted at install time rather than
            // mmap'd directly from the APK, working around the alignment check.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("sh.calvin.reorderable:reorderable:2.4.1")
    // Wake word: sherpa-onnx keyword spotting (Apache-2.0). Replaced Picovoice Porcupine,
    // whose free tier was terminated on 30 June 2026. Published via JitPack, not Maven
    // Central; the jitpack.io repository is declared in settings.gradle.kts.
    //
    // Note the group: "com.github.k2-fsa.sherpa-onnx", NOT "com.github.k2-fsa". sherpa-onnx is
    // a multi-module Gradle build, and JitPack publishes the repo root as
    // com.github.k2-fsa:sherpa-onnx with each submodule under
    // com.github.k2-fsa.<repo>:<submodule>. Depending on the ROOT drags in every platform
    // submodule at once — both the Android AAR and the JVM jar — and since both contain the
    // same com.k2fsa.sherpa.onnx classes, the build dies in checkReleaseDuplicateClasses with
    // ~100 "Duplicate class" errors. Targeting the Android submodule directly is the fix.
    //
    // The exclude is defensive belt-and-braces in case the Android module ever declares the
    // JVM one itself. Harmless if redundant. See docs/wake-word.md.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.5") {
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
    implementation("androidx.health.connect:connect-client:1.1.0-rc01")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
