import org.gradle.api.tasks.Sync

val runtimeDebugProbesEnabled = providers.gradleProperty("kmd.runtimeDebugProbes")
    .map { it.equals("true", ignoreCase = true).toString() }
    .orElse("false")

val generatedReaderRuntimeAssetsDir = layout.buildDirectory
    .dir("generated/assets/readerRuntime")
    .get()
    .asFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.kmd_reader"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.kmd_reader"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField(
                "boolean",
                "RUNTIME_VISUAL_DEBUG_PROBES",
                runtimeDebugProbesEnabled.get()
            )
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "RUNTIME_VISUAL_DEBUG_PROBES", "false")
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
    buildFeatures {
        buildConfig = true
        compose = true
    }
    sourceSets {
        getByName("main") {
            assets.srcDir(generatedReaderRuntimeAssetsDir)
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "kmd.integration",
                providers.gradleProperty("kmd.integration").orElse("false").get()
            )
            it.systemProperty(
                "kmd.apiBaseUrl",
                providers.gradleProperty("kmd.apiBaseUrl").orElse("http://127.0.0.1:3000/").get()
            )
        }
    }
}

val readerRuntimeDistDir = rootProject.layout.projectDirectory.dir("../../dist/reader-runtime")
val syncReaderRuntimeDist by tasks.registering(Sync::class) {
    onlyIf {
        readerRuntimeDistDir.asFile.resolve("index.html").isFile
    }
    from(readerRuntimeDistDir) {
        into("reader-runtime")
    }
    into(generatedReaderRuntimeAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(syncReaderRuntimeDist)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
