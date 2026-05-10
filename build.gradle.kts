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

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
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
