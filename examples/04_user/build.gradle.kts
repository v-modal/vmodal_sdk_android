plugins {
    id("com.android.library") version "8.4.2"
    kotlin("android") version "1.9.24"
    kotlin("jvm") version "1.9.24" apply false
}

android {
    namespace = "com.vmodal.sdk.examples.users"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    sourceSets["main"].java.srcDirs(
        "01_global_search_index/src/main/kotlin",
        "02_private_index_per_user/src/main/kotlin",
        "03_multiple_streams_per_user/src/main/kotlin",
        "04_product_catalog/src/main/kotlin",
    )

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
}

tasks.register("compileUserExamples") {
    group = "verification"
    description = "Compiles every user and business organization example."
    dependsOn("compileDebugKotlin")
}
