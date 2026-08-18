pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (wake word) is published via JitPack, not Maven Central — its
        // jitpack.yml installs the prebuilt Android AAR as com.github.k2-fsa:sherpa-onnx.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Carl's Brain"
include(":carlsbrain")
