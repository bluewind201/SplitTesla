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
        google()
        mavenCentral()
    }
}

rootProject.name = "SplitTesla"

include(":app")

// Protocol code lives in a plain Kotlin/JVM build so it can be tested without the Android SDK.
includeBuild("core")
