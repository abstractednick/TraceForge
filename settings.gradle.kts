pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "traceforge"
include(
    ":traceforge-runtime",
    ":traceforge-agent",
    ":traceforge-cli",
    ":traceforge-sample"
)
