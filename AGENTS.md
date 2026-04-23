# Eclipse plugin

- `WebJDT` HTTP server exposing JDT functionality.
- Pi extension `pi-extensions/nateclipse.ts` providing tools that hit WebJDT and other utilities.
- `CompletionSort` Improved completion and open type sorting
- `TabLabels` Tab label cleanup

Don't use java_* tools, they can't find Nateclipse classes because those are in a workspace for plugin development, not in the main workspace that runs WebJDT.

Don't bother with TSC.

## Pi documentation

- Main: C:\Apps\node\node_modules\@mariozechner\pi-coding-agent\README.md
- Additional: C:\Apps\node\node_modules\@mariozechner\pi-coding-agent\docs
- Examples: C:\Apps\node\node_modules\@mariozechner\pi-coding-agent\examples (extensions, custom tools, SDK)
- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers (docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md)
- Full Pi source: C:\Dev\oss\pi-mono
- Core tools: C:\Dev\oss\pi-mono\packages\coding-agent\src\core\tools
