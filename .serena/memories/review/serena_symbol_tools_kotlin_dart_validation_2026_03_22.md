Validated Serena symbol-tool behavior for Kotlin and Dart in project photo-finish on 2026-03-22.

Scope:
- Tools exercised: get_symbols_overview, find_symbol, find_referencing_symbols, insert_before_symbol, insert_after_symbol, replace_symbol_body, rename_symbol.
- Languages: Kotlin and Dart.
- Method: created temporary probe files for write-path validation; also performed read/reference checks on production Kotlin/Dart files.

Kotlin results:
- get_symbols_overview: PASS (class/method/property/object structures returned correctly)
- find_symbol: PASS with hierarchical name paths (e.g., Class/method)
- find_referencing_symbols: PASS with method-level references
- insert_before_symbol: PASS
- insert_after_symbol: PASS
- replace_symbol_body: PASS
- rename_symbol: PASS and references remained coherent for probe symbol usage

Dart results:
- get_symbols_overview: PARTIAL PASS; returns symbols but with anomalies (extra '<unnamed>' method, odd 'Field: String' in probe output)
- find_symbol: PARTIAL; method lookup works with flat names (e.g., 'startDetection', 'greet') but class-scoped paths (e.g., Class/method) often return empty
- find_referencing_symbols: PARTIAL; references found but categorized at File-level instead of precise Method-level in tested cases
- insert_before_symbol: PASS
- insert_after_symbol: PASS
- replace_symbol_body: PASS
- rename_symbol: PASS for tested method rename in probe file

Notes:
- Temporary probe files were removed after testing.
- gitnexus impact was attempted before probe symbol edits but returned target-not-found for new, unindexed probe symbols.
- No production code symbols were modified during this validation.