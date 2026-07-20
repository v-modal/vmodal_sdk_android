plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.vmodal.sdk.examples.fullapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vmodal.sdk.examples.fullapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets["main"].assets.srcDir("../asset")

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
        val sdkVersion = providers.gradleProperty("vmodalSdkVersion").getOrElse("1.0.0")
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

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty(
        "vmodalLive03Fullapp",
        providers.gradleProperty("vmodalLive03Fullapp").getOrElse("false"),
    )
    systemProperty(
        "vmodalFullappFixture",
        rootProject.file("asset/video_10frames.mp4").absolutePath,
    )
}
