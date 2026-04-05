from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path

import PyInstaller.__main__

APP_NAME = "sprint-sync-windows-desktop"


def run_pyinstaller_build(
    *,
    app_entry_path: Path,
    backend_dist_path: Path,
    node_runtime_path: Path,
    dist_path: Path,
    build_root_path: Path,
) -> None:
    build_root_path.mkdir(parents=True, exist_ok=True)

    for attempt in (1, 2):
        with tempfile.TemporaryDirectory(prefix=f"{APP_NAME}-", dir=build_root_path) as temp_dir:
            temp_dir_path = Path(temp_dir)
            work_path = temp_dir_path / "work"
            spec_path = temp_dir_path / "spec"

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
                str(work_path),
                "--specpath",
                str(spec_path),
                "--add-binary",
                f"{node_runtime_path}{os.pathsep}runtime",
                "--add-data",
                f"{backend_dist_path}{os.pathsep}backend/dist",
                str(app_entry_path),
            ]

            try:
                PyInstaller.__main__.run(args)
                return
            except FileNotFoundError as exc:
                # Rarely, the PYZ intermediate can disappear when packaging is invoked concurrently.
                if "PYZ-00.pyz" in str(exc) and attempt == 1:
                    print("[windows-desktop] Retrying after transient PyInstaller workspace issue...")
                    continue
                raise


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
    build_root_path = desktop_root_path / "build"

    run_pyinstaller_build(
        app_entry_path=app_entry_path,
        backend_dist_path=backend_dist_path,
        node_runtime_path=node_runtime_path,
        dist_path=dist_path,
        build_root_path=build_root_path,
    )

    output_path = dist_path / f"{APP_NAME}.exe"
    if not output_path.exists():
        raise SystemExit(f"Desktop executable was not produced at expected path: {output_path}")

    print(f"[windows-desktop] Built executable: {output_path}")
    return 0


def resolve_node_runtime_path() -> Path:
    resolved_path = shutil.which("node")
    if not resolved_path:
        return Path("node.exe")

    return Path(resolved_path).resolve()


if __name__ == "__main__":
    raise SystemExit(main())