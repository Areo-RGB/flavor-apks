Compared IntelliJ MCP vs Serena LSP in photo-finish for symbol search/overview/references/edit operations (Dart + Kotlin).

Runtime state during tests:
- Serena backend: LSP (project override)
- IntelliJ MCP connected (modules visible)

Read-only findings:
- Serena LSP:
  - Dart get_symbols_overview works.
  - Dart find_symbol class-scoped paths (Class/method) returned empty.
  - Dart find_symbol flat method names resolved.
  - Kotlin class-scoped find_symbol worked.
  - Kotlin get_symbols_overview worked.
- IntelliJ MCP:
  - search_in_files_by_text found Dart and Kotlin symbols/callsites reliably.
  - get_symbol_info returned rich symbol doc for Kotlin method.
  - get_symbol_info for Dart method returned empty documentation in tested position.

Edit-path findings (controlled temporary probes):
- Serena LSP symbol edits:
  - Dart rename_symbol succeeded.
  - Dart replace_symbol_body produced malformed output (signature duplication/corruption).
  - Kotlin rename_symbol + replace_symbol_body stayed structurally correct.
- IntelliJ MCP edits/refactors on probe files:
  - rename_refactoring on temporary Dart file failed (symbol not found / timeout depending path/index state).
  - rename_refactoring on temporary Kotlin file reported success with 0 usages and did not produce the expected rename in probe scenario.
  - replace_text_in_file calls timed out in this session for probe files.

Overall conclusion:
- For this repo/session, Serena LSP is stronger for Kotlin semantic symbol ops.
- Dart in Serena LSP remains flat-name-biased and fragile for symbol-body replacement.
- IntelliJ MCP is strong for indexed search/navigation and Kotlin symbol docs, but mutation operations can be sensitive to IDE index/readiness and file indexing context.

Recommended practical workflow:
- Kotlin: prefer Serena LSP symbol tools.
- Dart:
  - Use Serena LSP for overview + flat-name symbol lookup.
  - Avoid Serena replace_symbol_body for Dart unless validated immediately.
  - Use IntelliJ MCP search/symbol info as fallback for disambiguation/navigation when class-scoped lookup fails.