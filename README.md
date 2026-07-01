# GitHub Copilot CLI Launcher (JetBrains)

A small JetBrains plugin that launches the [GitHub Copilot CLI](https://github.com/github/copilot-cli)
inside the IDE's integrated terminal, installs it for you if it is not already on your `PATH`, and
lets the CLI connect back to the IDE via its `/ide` integration.

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

## Copilot CLI IDE connection (`/ide`)

This plugin also lets the Copilot CLI connect back to your IDE, the same way the CLI's `/ide`
command works in VS Code. While a project is open, the plugin runs a small local server and
advertises it to the CLI through a discovery file under `~/.copilot/ide/`. The CLI can then
connect (auto-connect, or via its `/ide` picker) and use the live IDE context.

Once connected, the CLI can:

- **Read diagnostics** from your open files (errors, warnings, etc.).
- **Read the current selection** in the active editor.
- **Open edit diffs in the IDE**: when enabled in the CLI, proposed file edits are shown as a
  diff you accept or reject instead of being confirmed in the terminal.
- **Track selection changes**, so the code you highlight is available to the CLI prompt.

Two editor context-menu actions are added for pushing context to a connected session:

- **Add File Reference to Copilot CLI Prompt**
- **Add Selection to Copilot CLI Prompt**

The connection is local only (a Unix domain socket on macOS/Linux, a named pipe on Windows),
is authenticated with a per-session token, and the discovery file is removed when the project
closes. Everything is scoped per open project.

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
│   ├── CopilotCliLauncher.kt      # Opens the CLI in the integrated terminal
│   ├── LaunchCopilotCliAction.kt  # Tools menu action
│   └── ide/                       # Copilot CLI `/ide` connection (IDE-side MCP server)
│       ├── IdeMcpProjectService.kt   # Per-project lifecycle + IDE tool implementations
│       ├── McpServer.kt              # MCP-over-HTTP protocol handling + notifications
│       ├── IdeSocketServer.kt        # Unix domain socket / Windows named pipe transport
│       ├── IdeLockFile.kt            # Discovery lock file under ~/.copilot/ide/
│       └── ...
└── resources/META-INF/
    └── plugin.xml
```

## License

MIT. See [LICENSE](./LICENSE).
