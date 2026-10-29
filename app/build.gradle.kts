plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "cn.yukehelper.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.yukehelper.android.prototype"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.2.15"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // Optional custom debug key location for isolated build environments.
    providers.gradleProperty("prototypeDebugKeystore").orNull?.let { location ->
        signingConfigs.getByName("debug") { storeFile = file(location) }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
