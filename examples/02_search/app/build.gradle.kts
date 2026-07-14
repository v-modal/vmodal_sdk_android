import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val apiToken = providers.gradleProperty("VMODAL_API_KEY")
    .orElse(providers.environmentVariable("VMODAL_API_KEY"))
    .orElse(providers.environmentVariable("TEST_CLIENT_CLERK_USER_API_TOKEN"))
    .orElse(providers.provider { localProperties.getProperty("VMODAL_API_KEY", "") })
    .orElse("")

android {
    namespace = "com.vmodal.sdk.examples.search"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vmodal.sdk.examples.search"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "VMODAL_API_KEY", apiToken.get().asBuildConfigString())
    }

    buildFeatures {
        buildConfig = true
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
    implementation(project(":vmodal-sdk-android"))

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
