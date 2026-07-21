plugins {
    id("com.android.library") version "8.4.2"
    kotlin("android") version "1.9.24"
    kotlin("jvm") version "1.9.24" apply false
}

android {
    namespace = "com.vmodal.sdk.examples"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}

tasks.register("compileCookbook") {
    group = "verification"
    description = "Compiles every canonical Android integration cookbook source."
    dependsOn("compileDebugKotlin")
}
