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
        maven("https://plugins.gradle.org/m2/")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

// TODO: Update project's name.
rootProject.name = "GodotAndroidPluginTemplate"
include(":plugin")
