Switched New Relic startup from `--dart-define` to a local `.env` file.

Changes made:
- Added `flutter_dotenv: ^6.0.0` to `pubspec.yaml`.
- Added `.env` as a Flutter asset so the file is bundled at build time.
- Updated `lib/main.dart` to:
  - `await dotenv.load(fileName: '.env', isOptional: true);`
  - read `NEW_RELIC_ANDROID_TOKEN` / `NEW_RELIC_IOS_TOKEN` from `dotenv.env`
  - keep New Relic disabled when the token is missing
- Updated `README.md` to describe the `.env` workflow instead of `--dart-define`.

Verification:
- `flutter pub get` passed.
- `flutter analyze` passed.

Note:
- The local `.env` file is still ignored by git and stays machine-local.
