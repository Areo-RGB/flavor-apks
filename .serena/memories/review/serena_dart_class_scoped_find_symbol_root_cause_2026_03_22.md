Investigated unresolved Dart class-scoped find_symbol paths after PATH cleanup.

Findings:
- PATH cleanup (removing C:\tools\dart-sdk\bin) did not resolve class-scoped path lookup issue.
- Reproduced issue deterministically in LSP backend by applying symbol edits on a probe Dart file:
  - insert_before_symbol + insert_after_symbol + replace_symbol_body + rename_symbol
  - resulting file became structurally corrupted (e.g., duplicated signature fragments), then get_symbols_overview showed '<unnamed>' and odd field classification.
- Even on clean files in LSP backend, Dart find_symbol is flat-name oriented and class/member name paths like Class/method return empty.

Backend test:
- Setting .serena/project.yml language_backend: JetBrains and activating in a standalone serena CLI process reports:
  "Cannot activate project ... requires JetBrains backend, but this session was initialized with LSP."
- This confirms backend is session-fixed at startup and true hierarchical fix requires a JetBrains-initialized Serena server (or upstream LSP bugfix).

Conclusion:
- Not a PATH issue.
- Root cause is Serena 0.1.4 Dart behavior in LSP mode (flat symbol naming and fragile body ranges for symbol-body edits).
- Practical workaround in LSP mode: use flat method names for find_symbol, avoid replace_symbol_body for Dart methods, and use line/file edits for Dart body changes.