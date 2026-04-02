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

## Start Backend Only

```powershell
npm run windows:start
```

## Backend Environment Variables

- `WINDOWS_TCP_HOST` (default: `0.0.0.0`)
- `WINDOWS_TCP_PORT` (default: `9000`)
- `WINDOWS_HTTP_HOST` (default: `0.0.0.0`)
- `WINDOWS_HTTP_PORT` (default: `8787`)
