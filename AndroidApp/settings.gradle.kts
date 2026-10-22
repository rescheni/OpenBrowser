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
        // GeckoView（Firefox 内核）只发布在 Mozilla 自己的 Maven 仓库
        maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "OpenBrowserMobile"
include(":app")
