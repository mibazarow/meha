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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (offline OCR, Apache-2.0) is published only on JitPack:
        // https://github.com/adaptech-cz/Tesseract4Android
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ANDharness"
include(":app")
