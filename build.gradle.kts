plugins {
    kotlin("jvm") version "1.9.24"
}

group = "com.vmodal"
version = "1.0.0"

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

kotlin {
    jvmToolchain(17)
}

val sim by sourceSets.creating {
    kotlin.srcDir("src/sim/kotlin")
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

val live by sourceSets.creating {
    kotlin.srcDir("src/live/kotlin")
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.register<JavaExec>("regressionTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vmodal.sdk.VmodalSdkRegressionTestKt")
}

tasks.test {
    dependsOn("regressionTest")
    enabled = false
}

tasks.register<JavaExec>("runSim") {
    dependsOn(tasks.named(sim.classesTaskName))
    classpath = sim.runtimeClasspath
    mainClass.set("com.vmodal.sdk.SimAppKt")
}

tasks.register<JavaExec>("liveTest") {
    dependsOn(tasks.named(live.classesTaskName))
    classpath = live.runtimeClasspath
    mainClass.set("com.vmodal.sdk.LiveTestKt")
}
