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
            mavenLocal()
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
