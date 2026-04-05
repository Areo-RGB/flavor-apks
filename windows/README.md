# Sprint Sync Windows App

This folder contains the Windows coordinator app stack:

- `backend/`: Node.js TCP server compatible with the Android frame protocol
- `ui/`: Vite React operator dashboard (Tailwind CSS)

## Install

From repository root:

```powershell
npm run windows:install
```

## Run in Development

From repository root:

```powershell
npm run windows:dev
```

This starts:

- backend on `http://localhost:8787`
- UI on `http://localhost:5173`

## Build

```powershell
npm run windows:build
```

## Install Python Packaging Dependencies

From repository root:

```powershell
npm run windows:python:install
```

## Package Desktop App (PyInstaller + Webview)

From repository root:

```powershell
npm run windows:package:exe
```

Output files:

- `windows/backend/dist/server.cjs` (bundled backend entry)
- `windows/backend/dist/ui/` (frontend bundle served by backend)
- `windows/desktop/dist/sprint-sync-windows-desktop.exe` (desktop app wrapper)

The desktop wrapper bundles a Node runtime, launches the backend in the background, and opens the UI in a native webview window.

Run the packaged executable:

```powershell
cd windows/desktop/dist
.\sprint-sync-windows-desktop.exe
```

## Start Backend Only

```powershell
npm run windows:start
```

## Backend Environment Variables

- `WINDOWS_TCP_HOST` (default: `0.0.0.0`)
- `WINDOWS_TCP_PORT` (default: `9000`)
- `WINDOWS_HTTP_HOST` (default: `0.0.0.0`)
- `WINDOWS_HTTP_PORT` (default: `8787`)
- `WINDOWS_UI_DIST_DIR` (optional override for frontend bundle location)
