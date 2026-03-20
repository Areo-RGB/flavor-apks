Updated `C:/Users/paul/projects/photo-finish/.serena/project.yml` to enable Serena language servers for this repo by changing `languages` from empty list to `[dart, kotlin]`.

Validation in a fresh Serena CLI process:
- `serena activate-project --project C:/Users/paul/projects/photo-finish` reports `Programming languages: dart, kotlin`.
- `serena get-symbols-overview --relative-path lib/main.dart` returns symbols successfully.

Note: Existing already-running Serena MCP sessions may need restart/reconnect to pick up updated project config and spawn LSPs.