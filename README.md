# sprint_sync

Sprint Sync v1

## Getting Started

This project is a starting point for a Flutter application.

A few resources to get you started if this is your first Flutter project:

- [Lab: Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Cookbook: Useful Flutter samples](https://docs.flutter.dev/cookbook)

## New Relic

The app reads the New Relic application token from the local `.env` file on
Android and iOS.

Add these keys to `.env`:

```env
NEW_RELIC_ANDROID_TOKEN=<your-token>
NEW_RELIC_IOS_TOKEN=<your-token>
```

When the matching token is present, the Flutter agent starts on app launch and
navigation tracking is enabled.

No `--dart-define` is needed anymore.

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev/), which offers tutorials,
samples, guidance on mobile development, and a full API reference.
