rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // CloudStream's NiceHttp and NewPipeExtractor artifacts are published via JitPack.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.Blatzar")
                includeGroup("com.github.teamnewpipe")
                // NewPipeExtractor pulls nanojson from the TeamNewPipe GitHub organization.
                includeGroup("com.github.TeamNewPipe")
            }
        }
    }
}

include(":composeApp")
include(":androidApp")
include(":baselineprofile")
