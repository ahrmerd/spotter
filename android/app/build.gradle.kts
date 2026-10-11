import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.ahmed.spotter"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.ahmed.spotter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing: the key never lives in the repository. Put its location in android/keystore.properties
    // (git-ignored) or pass SPOTTER_KEYSTORE / SPOTTER_KEYSTORE_PASSWORD / SPOTTER_KEY_ALIAS / SPOTTER_KEY_PASSWORD.
    // Without either, `assembleRelease` produces an unsigned APK. See android/README.md.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signing(name: String, env: String): String? = keystoreProps.getProperty(name) ?: System.getenv(env)
    val releaseKeystore = signing("storeFile", "SPOTTER_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signing("storePassword", "SPOTTER_KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "SPOTTER_KEY_ALIAS")
                keyPassword = signing("keyPassword", "SPOTTER_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses newer java.* APIs than minSdk 26 offers.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets {
        // Room's MigrationTestHelper reads the exported schema JSONs (1.json, 2.json) from the test assets.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        // Dependency versions are pinned on purpose (reproducible builds, tested set); upgrades are a planned task.
        // targetSdk 35 is the newest platform installed on the build machine.
        disable += setOf("GradleDependency", "OldTargetApi")
        // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is a Play Store policy issue only; Spotter is distributed as an APK and the
        // permission is used once, after an explanation dialog, to open the system battery prompt.
        disable += "BatteryLife"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "1536m"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val compose = platform("androidx.compose:compose-bom:2024.12.01")
    val room = "2.6.1"
    implementation(compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // HTTP/2 out of the box (the desktop version needed curl for this)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") // YouTube resolver (GPL-3.0, see README)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.room:room-testing:$room")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
}
