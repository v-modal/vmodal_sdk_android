import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    `java-library`
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.15.1"
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("project-report")
}

group = "com.vmodal"
version = "1.0.0"

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
}

tasks.register("verifyIdeSources") {
    doLast {
        val ids = configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
        val result = dependencies.createArtifactResolutionQuery()
            .forComponents(ids)
            .withArtifacts(JvmLibrary::class.java, SourcesArtifact::class.java)
            .execute()
        result.resolvedComponents.flatMap { it.getArtifacts(SourcesArtifact::class.java) }
            .filterIsInstance<ResolvedArtifactResult>()
            .forEach { it.file }
    }
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

dokka {
    moduleName.set("VModal Android SDK")
    dokkaPublications.html {
        val docsOutput = providers.gradleProperty("vmodalDocsOutput")
        outputDirectory.set(
            if (docsOutput.isPresent) file(docsOutput.get())
            else layout.buildDirectory.dir("dokka/html").get().asFile
        )
        failOnWarning.set(true)
        suppressInheritedMembers.set(true)
    }
    dokkaSourceSets.main {
        documentedVisibilities.set(setOf(VisibilityModifier.Public))
        reportUndocumented.set(true)
        skipEmptyPackages.set(true)
        suppressedFiles.from(file("src/main/kotlin/com/vmodal/sdk/Routes.kt"))
        includes.from(file("DOC_REF.md"))
    }
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

val compatibilityRepo = layout.buildDirectory.dir("compat-repo")
publishing.repositories.maven {
    name = "Compatibility"
    url = uri(compatibilityRepo)
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

val deterministicTests = listOf(
    Triple("regressionTest", "VmodalSdkRegressionTest.kt", "com.vmodal.sdk.VmodalSdkRegressionTestKt"),
    Triple("compatibilityBaselineTest", "CompatibilityBaselineTest.kt", "com.vmodal.sdk.CompatibilityBaselineTestKt"),
    Triple("transportIntegrationTest", "TransportIntegrationTest.kt", "com.vmodal.sdk.TransportIntegrationTestKt"),
    Triple("p2HttpTest", "P2HttpRegressionTest.kt", "com.vmodal.sdk.P2HttpRegressionTestKt"),
    Triple("coroutineApiRegressionTest", "CoroutineApiRegressionTest.kt", "com.vmodal.sdk.CoroutineApiRegressionTestKt"),
    Triple("vModalFacadeTest", "VModalFacadeTest.kt", "com.vmodal.sdk.VModalFacadeTestKt"),
    Triple("diagnosticsRegressionTest", "DiagnosticsRegressionTest.kt", "com.vmodal.sdk.DiagnosticsRegressionTestKt"),
)

deterministicTests.forEach { (taskName, fileName, className) ->
    if (file("src/test/kotlin/com/vmodal/sdk/$fileName").isFile) {
        tasks.register<JavaExec>(taskName) {
            dependsOn(tasks.testClasses)
            if (taskName == "compatibilityBaselineTest") {
                dependsOn(
                    "apiCheck",
                    "dokkaGeneratePublicationHtml",
                    "publishAllPublicationsToCompatibilityRepository",
                )
            }
            classpath = sourceSets.test.get().runtimeClasspath
            mainClass.set(className)
        }
        tasks.test { dependsOn(taskName) }
    }
}

tasks.register<Exec>("artifactConsumerTest") {
    dependsOn("publishAllPublicationsToCompatibilityRepository")
    workingDir("integration/consumer")
    commandLine(
        file("gradlew").absolutePath,
        "-p", ".",
        "--no-daemon",
        "--dependency-verification", "strict",
        "-PvmodalMavenRepo=${compatibilityRepo.get().asFile.absolutePath}",
        "-PvmodalSdkVersion=$version",
        "clean",
        "classes",
    )
}

tasks.register<Exec>("cookbookCheck") {
    group = "verification"
    description = "Compiles the canonical Android integration cookbook sources."
    workingDir(layout.projectDirectory)
    commandLine(
        file("examples/02_search/gradlew").absolutePath,
        "-p", file("examples/01_starter").absolutePath,
        "--no-daemon",
        "--dependency-verification", "strict",
        ":compileDebugKotlin",
    )
}

tasks.test { enabled = false }

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
