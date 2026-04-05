from __future__ import annotations

import os
import shutil
from pathlib import Path

import PyInstaller.__main__

APP_NAME = "sprint-sync-windows-desktop"


def main() -> int:
    desktop_root_path = Path(__file__).resolve().parent
    windows_root_path = desktop_root_path.parent
    app_entry_path = desktop_root_path / "app.py"
    backend_dist_path = windows_root_path / "backend" / "dist"
    backend_entry_path = backend_dist_path / "server.cjs"
    backend_ui_index_path = backend_dist_path / "ui" / "index.html"
    node_runtime_path = resolve_node_runtime_path()

    if not backend_entry_path.exists() or not backend_ui_index_path.exists():
        raise SystemExit(
            "Missing packaged backend bundle. Run npm run package --workspace @sprint-sync/windows-backend first."
        )

    if not node_runtime_path.exists():
        raise SystemExit(
            "Node runtime was not found. Ensure Node.js is installed and available in PATH before packaging."
        )

    dist_path = desktop_root_path / "dist"
    build_path = desktop_root_path / "build"

    args = [
        "--noconfirm",
        "--clean",
        "--onefile",
        "--windowed",
        "--name",
        APP_NAME,
        "--distpath",
        str(dist_path),
        "--workpath",
        str(build_path),
        "--specpath",
        str(build_path),
        "--add-binary",
        f"{node_runtime_path}{os.pathsep}runtime",
        "--add-data",
        f"{backend_dist_path}{os.pathsep}backend/dist",
        str(app_entry_path),
    ]

    PyInstaller.__main__.run(args)

    output_path = dist_path / f"{APP_NAME}.exe"
    print(f"[windows-desktop] Built executable: {output_path}")
    return 0


def resolve_node_runtime_path() -> Path:
    resolved_path = shutil.which("node")
    if not resolved_path:
        return Path("node.exe")

    return Path(resolved_path).resolve()


if __name__ == "__main__":
    raise SystemExit(main())