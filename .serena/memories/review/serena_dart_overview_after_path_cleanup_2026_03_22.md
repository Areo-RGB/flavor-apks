Re-tested Serena Dart symbol tooling after removing standalone Dart SDK path entry C:\tools\dart-sdk\bin from user PATH (Flutter Dart remained first).

Validation date: 2026-03-22.

Probe 1 (minimal class with greet/greetTwice):
- get_symbols_overview => {Class: ProbeSymbols, Method: greet,greetTwice}
- No '<unnamed>' method and no odd field classification.

Probe 2 (previously problematic shape: top-level const + top-level function + class methods):
- get_symbols_overview => {Variable: probeDartVersion, Function: probeTopLevel, Class: ProbeSymbols, Method: greet,greetRepeat}
- No '<unnamed>' method and no 'Field: String' anomaly.

Observation:
- Class-scoped method lookup in Dart still appears limited (e.g., ProbeSymbols/greetTwice can return empty while flat method name works), but overview classification anomaly was not reproducible after PATH cleanup in this session.