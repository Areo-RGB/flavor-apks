Configured project-level Serena backend for photo-finish to JetBrains.

Changes applied:
- .serena/project.yml
  - languages: [dart, kotlin]
  - language_backend: JetBrains

Validation:
- IntelliJ MCP is connected to project path C:/Users/paul/projects/photo-finish.
- get_project_modules returns sprint_sync + sprint_sync_android.
- get_repositories returns Git root.

Important runtime note:
- Current Serena MCP session remains initialized as LSP (session-fixed at startup), so the backend switch will only take effect after restarting/reconnecting Serena/Codex session with project activation under JetBrains backend.