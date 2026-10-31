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
        // NAVER Map SDK for the dashboard map.
        maven {
            url = uri("https://repository.map.naver.com/archive/maven")
            content { includeGroup("com.naver.maps") }
        }
    }
}

rootProject.name = "SplitTesla"

include(":app")

// Protocol code lives in a plain Kotlin/JVM build so it can be tested without the Android SDK.
includeBuild("core")
