import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm") version "1.9.24"
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("project-report")
}

group = "com.vmodal"
version = "1.0.0"

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
}

kotlin {
    jvmToolchain(17)
}

mavenPublishing {
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = true,
        )
    )
    coordinates("com.vmodal", "vmodal-sdk-android", version.toString())
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    pom {
        name.set("VModal Android SDK")
        description.set("Multimodal video and image search SDK for Android and Kotlin")
        inceptionYear.set("2026")
        url.set("https://github.com/v-modal/vmodal_sdk_android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("v-modal")
                name.set("V-Modal")
                url.set("https://v-modal.com")
            }
        }
        scm {
            url.set("https://github.com/v-modal/vmodal_sdk_android")
            connection.set("scm:git:git://github.com/v-modal/vmodal_sdk_android.git")
            developerConnection.set("scm:git:ssh://git@github.com/v-modal/vmodal_sdk_android.git")
        }
    }
}

val githubPackagesUser = providers.environmentVariable("GITHUB_PACKAGES_USERNAME")
val githubPackagesToken = providers.environmentVariable("GITHUB_PACKAGES_TOKEN")
if (githubPackagesUser.isPresent && githubPackagesToken.isPresent) {
    publishing.repositories.maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/v-modal/vmodal_sdk_android")
        credentials {
            username = githubPackagesUser.get()
            setPassword(githubPackagesToken.get())
        }
    }
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

if (file("src/test/kotlin/com/vmodal/sdk/VmodalSdkRegressionTest.kt").isFile) {
    tasks.register<JavaExec>("regressionTest") {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("com.vmodal.sdk.VmodalSdkRegressionTestKt")
    }

    tasks.test {
        dependsOn("regressionTest")
        enabled = false
    }
}

if (file("src/sim/kotlin/com/vmodal/sdk/SimApp.kt").isFile) {
    tasks.register<JavaExec>("runSim") {
        dependsOn(tasks.named(sim.classesTaskName))
        classpath = sim.runtimeClasspath
        mainClass.set("com.vmodal.sdk.SimAppKt")
    }
}

if (file("src/live/kotlin/com/vmodal/sdk/LiveTest.kt").isFile) {
    tasks.register<JavaExec>("liveTest") {
        dependsOn(tasks.named(live.classesTaskName))
        classpath = live.runtimeClasspath
        mainClass.set("com.vmodal.sdk.LiveTestKt")
    }
}
