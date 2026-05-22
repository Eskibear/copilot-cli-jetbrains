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
    // Kotlin runtime, Gson and JNA are normally provided by the IntelliJ platform at
    // runtime, but the standalone selfcheck JavaExec needs them on the JavaExec classpath.
    runtimeOnly(kotlin("stdlib"))
    runtimeOnly("com.google.code.gson:gson:2.11.0")
    runtimeOnly("net.java.dev.jna:jna:5.17.0")
    runtimeOnly("net.java.dev.jna:jna-platform:5.17.0")
}

tasks.register<JavaExec>("runSelfCheck") {
    group = "verification"
    description = "Runs the IDE bridge selfcheck (no sandbox IDE needed)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.github.eskibear.copilotcli.bridge.BridgeSelfCheckKt"
    standardInput = System.`in`
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
