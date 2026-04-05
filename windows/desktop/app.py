from __future__ import annotations

import ctypes
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import webview

APP_TITLE = "Sprint Sync Windows"
DEFAULT_HTTP_PORT = 8787
DEFAULT_TCP_PORT = 9000
STARTUP_TIMEOUT_SECONDS = 20.0
LOCAL_UI_HOST = "127.0.0.1"
NETWORK_TCP_HOST = "0.0.0.0"


def main() -> int:
    backend_entry_path = find_backend_entry_path()
    if not backend_entry_path.exists():
        show_error(
            "Unable to start Sprint Sync.\n\n"
            f"Backend bundle was not found at:\n{backend_entry_path}"
        )
        return 1

    node_runtime_path = find_node_runtime_path()
    if not node_runtime_path.exists():
        show_error(
            "Unable to start Sprint Sync.\n\n"
            f"Node runtime was not found at:\n{node_runtime_path}"
        )
        return 1

    http_port = resolve_port_from_env_or_default("WINDOWS_HTTP_PORT", DEFAULT_HTTP_PORT)
    tcp_port = resolve_port_from_env_or_default("WINDOWS_TCP_PORT", DEFAULT_TCP_PORT)

    if not is_port_available(NETWORK_TCP_HOST, tcp_port):
        show_error(
            "Sprint Sync backend could not start for device connections.\n\n"
            f"TCP port {tcp_port} is already in use.\n"
            "Close the conflicting process or set WINDOWS_TCP_PORT to a free port."
        )
        return 1

    if http_port == tcp_port or not is_port_available(LOCAL_UI_HOST, http_port):
        http_port = find_available_port(LOCAL_UI_HOST, exclude_ports={tcp_port})

    backend_process = start_backend_process(
        node_runtime_path=node_runtime_path,
        backend_entry_path=backend_entry_path,
        http_port=http_port,
        tcp_port=tcp_port,
    )

    backend_url = f"http://127.0.0.1:{http_port}"

    try:
        wait_for_backend_ready(backend_process=backend_process, backend_url=backend_url)
    except Exception as error:  # noqa: BLE001
        stop_backend_process(backend_process)
        show_error(
            "Sprint Sync backend failed to start.\n\n"
            f"{error}"
        )
        return 1

    window = webview.create_window(APP_TITLE, backend_url, width=1280, height=840, min_size=(960, 640))

    def on_window_closing(*_args) -> None:
        stop_backend_process(backend_process)

    window.events.closing += on_window_closing

    try:
        webview.start()
    finally:
        stop_backend_process(backend_process)

    return 0


def resolve_port_from_env_or_default(env_name: str, default_value: int) -> int:
    raw_value = os.environ.get(env_name)
    if not raw_value:
        return default_value

    try:
        parsed_value = int(raw_value)
    except ValueError:
        return default_value

    if parsed_value < 1 or parsed_value > 65535:
        return default_value

    return parsed_value


def resource_root_path() -> Path:
    if hasattr(sys, "_MEIPASS"):
        return Path(getattr(sys, "_MEIPASS"))

    return Path(__file__).resolve().parent


def find_backend_entry_path() -> Path:
    resource_root = resource_root_path()
    script_dir = Path(__file__).resolve().parent

    candidate_paths = [
        resource_root / "backend" / "dist" / "server.cjs",
        script_dir.parent / "backend" / "dist" / "server.cjs",
        Path(sys.executable).resolve().parent / "backend" / "dist" / "server.cjs",
    ]

    for candidate_path in candidate_paths:
        if candidate_path.exists():
            return candidate_path

    return candidate_paths[0]


def find_node_runtime_path() -> Path:
    resource_root = resource_root_path()

    candidate_paths = [
        resource_root / "runtime" / "node.exe",
    ]

    resolved_from_path = shutil.which("node")
    if resolved_from_path:
        candidate_paths.append(Path(resolved_from_path).resolve())

    for candidate_path in candidate_paths:
        if candidate_path.exists():
            return candidate_path

    return candidate_paths[0]


def startupinfo_for_background_process() -> subprocess.STARTUPINFO | None:
    if os.name != "nt":
        return None

    startup_info = subprocess.STARTUPINFO()
    startup_info.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    return startup_info


def start_backend_process(
    node_runtime_path: Path,
    backend_entry_path: Path,
    http_port: int,
    tcp_port: int,
) -> subprocess.Popen:
    env = os.environ.copy()
    env["WINDOWS_HTTP_HOST"] = env.get("WINDOWS_HTTP_HOST", LOCAL_UI_HOST)
    env["WINDOWS_TCP_HOST"] = env.get("WINDOWS_TCP_HOST", NETWORK_TCP_HOST)
    env["WINDOWS_HTTP_PORT"] = str(http_port)
    env["WINDOWS_TCP_PORT"] = str(tcp_port)

    creation_flags = 0
    if os.name == "nt":
        creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)

    return subprocess.Popen(  # noqa: S603
        [str(node_runtime_path), str(backend_entry_path)],
        env=env,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        startupinfo=startupinfo_for_background_process(),
        creationflags=creation_flags,
    )


def wait_for_backend_ready(backend_process: subprocess.Popen, backend_url: str) -> None:
    deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
    healthcheck_url = f"{backend_url}/api/health"

    while time.monotonic() < deadline:
        exit_code = backend_process.poll()
        if exit_code is not None:
            raise RuntimeError(f"Backend process exited with code {exit_code}.")

        try:
            with urllib.request.urlopen(healthcheck_url, timeout=1.0) as response:  # noqa: S310
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError, OSError):
            time.sleep(0.2)

    raise RuntimeError(f"Backend did not become healthy within {STARTUP_TIMEOUT_SECONDS:.0f} seconds.")


def stop_backend_process(backend_process: subprocess.Popen) -> None:
    if backend_process.poll() is not None:
        return

    backend_process.terminate()
    try:
        backend_process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        backend_process.kill()


def is_port_available(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as candidate_socket:
        candidate_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            candidate_socket.bind((host, port))
            return True
        except OSError:
            return False


def find_available_port(host: str, exclude_ports: set[int] | None = None) -> int:
    excluded = exclude_ports or set()

    while True:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as candidate_socket:
            candidate_socket.bind((host, 0))
            chosen_port = int(candidate_socket.getsockname()[1])
            if chosen_port not in excluded:
                return chosen_port


def show_error(message: str) -> None:
    if os.name == "nt":
        ctypes.windll.user32.MessageBoxW(None, message, APP_TITLE, 0x10)
        return

    print(message)


if __name__ == "__main__":
    raise SystemExit(main())