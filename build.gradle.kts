plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

intellijPlatform {
    instrumentCode = false

    pluginConfiguration {
        version = providers.gradleProperty("version").orElse(project.version.toString())
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
