plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val testSigning = listOf(
    "AUREDIAL_KEYSTORE", "AUREDIAL_STORE_PASSWORD", "AUREDIAL_KEY_ALIAS", "AUREDIAL_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val useTestKey = testSigning.values.any { it != null }
if (useTestKey) {
    require(testSigning.values.all { !it.isNullOrBlank() }) { "Set all four AureDial test signing variables." }
    require(file(testSigning.getValue("AUREDIAL_KEYSTORE")!!).isFile) { "The AureDial test keystore does not exist." }
}

android {
    namespace = "dev.auredial.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.auredial.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.0-beta04"
    }
    buildFeatures { compose = true }
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (useTestKey) {
        signingConfigs.getByName("debug") {
            storeFile = file(testSigning.getValue("AUREDIAL_KEYSTORE")!!)
            storePassword = testSigning.getValue("AUREDIAL_STORE_PASSWORD")
            keyAlias = testSigning.getValue("AUREDIAL_KEY_ALIAS")
            keyPassword = testSigning.getValue("AUREDIAL_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":domain"))
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
