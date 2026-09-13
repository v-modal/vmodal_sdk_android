plugins {
    kotlin("jvm") version "1.9.24"
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":vmodal-sdk-android"))
    implementation("com.squareup.moshi:moshi:1.15.2")
}

application {
    mainClass.set("com.vmodal.sdk.examples.jsonmetadata.MainKt")
}
