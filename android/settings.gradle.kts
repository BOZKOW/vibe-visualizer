// Vibe Visualizer for Android: a native app (Kotlin, Jetpack Compose) that uses your Vibe Visualizer server for the
// parts that need it (Spicy Lyrics, Spotify lyrics and Canvas, NetEase) and does everything else on the phone.
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
rootProject.name = "VibeVisualizer"
include(":app")
