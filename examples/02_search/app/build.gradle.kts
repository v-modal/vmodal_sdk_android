plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.vmodal.sdk.examples.search"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vmodal.sdk.examples.search"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    if (providers.gradleProperty("vmodalUseMavenLocal").orNull == "true") {
        val sdkVersion = providers.gradleProperty("vmodalSdkVersion").get()
        implementation("com.vmodal:vmodal-sdk-android:$sdkVersion")
    } else {
        implementation(project(":vmodal-sdk-android"))
    }

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("io.coil-kt:coil-compose:2.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
