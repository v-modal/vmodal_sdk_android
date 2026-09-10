check(JavaVersion.current() == JavaVersion.VERSION_17) {
    "VModal Full Search requires JDK 17, but Gradle is using ${System.getProperty("java.version")}. " +
        "In Android Studio, set Settings > Build Tools > Gradle > Gradle JDK to 17."
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("vmodalUseMavenLocal").orNull == "true") {
            val sdkRepo = providers.gradleProperty("vmodalMavenRepo").get()
            exclusiveContent {
                forRepository {
                    maven {
                        name = "vmodalCi"
                        url = uri(sdkRepo)
                    }
                }
                filter { includeModule("com.vmodal", "vmodal-sdk-android") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "vmodal-full-app-example"

include(":app")
if (providers.gradleProperty("vmodalUseMavenLocal").orNull != "true") {
    include(":vmodal-sdk-android")
    project(":vmodal-sdk-android").projectDir = file("../..")
}
