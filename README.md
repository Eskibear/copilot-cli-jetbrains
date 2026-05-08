# GitHub Copilot CLI Launcher (JetBrains)

A small JetBrains plugin that launches the [GitHub Copilot CLI](https://github.com/github/copilot-cli)
inside the IDE's integrated terminal, and installs it for you if it is not already on your `PATH`.

## What it does

Adds a single action under **Tools | Launch GitHub Copilot CLI**.

When invoked:

1. The plugin checks whether the `copilot` binary is reachable from the IDE's `PATH`.
2. If it is, a new terminal tab named `Copilot CLI` is opened in the project root and `copilot` is executed.
3. If it is not, you are asked whether to install it. On confirmation, a terminal tab is opened
   running the official installer:
   - Windows: `winget install --id GitHub.Copilot -e`
   - macOS / Linux: `curl -fsSL https://gh.io/copilot-install | bash`

After install completes, re-invoke the action to launch the CLI.

## Compatibility

- IntelliJ Platform `2026.1` and newer (`sinceBuild = "261"`, no `untilBuild`).
- Works in any IDE that bundles the Terminal plugin (IDEA, PyCharm, WebStorm, GoLand, Rider, etc.).

## Build

Requires JDK 21 (the project uses a Gradle Kotlin toolchain so any 21 JDK works).

```sh
./gradlew build
```

Artifacts land in `build/distributions/`.

To try it in a sandbox IDE:

```sh
./gradlew runIde
```

## Layout

```
src/main/
├── kotlin/io/github/eskibear/copilotcli/
│   ├── CopilotCliService.kt       # PATH lookup for the `copilot` binary
│   ├── CopilotCliInstaller.kt     # Platform-specific install command
│   └── LaunchCopilotCliAction.kt  # Tools menu action
└── resources/META-INF/
    └── plugin.xml
```

## License

MIT. See [LICENSE](./LICENSE).
