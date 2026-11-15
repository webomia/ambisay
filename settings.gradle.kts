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
        // El Meta Wearables Device Access Toolkit (com.meta.wearable:mwdat-*) se publica en Maven Central:
        // no hace falta token ni repositorio privado.
        mavenCentral()
    }
}
rootProject.name = "RayBanGrok"
include(":app")
