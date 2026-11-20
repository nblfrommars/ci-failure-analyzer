import json
import os
import re
import subprocess
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP


mcp = FastMCP("deploy-mcp")
PROJECT_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = PROJECT_ROOT / "deploy" / "staging.yml"
STATE_FILE = PROJECT_ROOT / "deploy" / ".staging-state.json"
IMAGE_PATTERN = re.compile(r"^ghcr\.io/nblfrommars/ci-sandbox:sha-[0-9a-f]{40}$")


def _run_docker(*args: str, timeout: int = 120) -> str:
    command = ["docker", "compose", "-f", str(COMPOSE_FILE), *args]
    result = subprocess.run(command, cwd=PROJECT_ROOT, capture_output=True,
                            text=True, timeout=timeout, check=False)
    output = (result.stdout + "\n" + result.stderr).strip()
    if result.returncode != 0:
        raise RuntimeError(f"Docker command failed ({result.returncode}): {output[-2000:]}")
    return output[-4000:]


def _validate_staging_image(image: str) -> None:
    if not IMAGE_PATTERN.fullmatch(image):
        raise ValueError("Chỉ chấp nhận image GHCR của ci-sandbox có tag commit SHA 40 ký tự; chỉ deploy staging.")


def _read_state() -> dict[str, Any]:
    if not STATE_FILE.exists():
        return {}
    return json.loads(STATE_FILE.read_text(encoding="utf-8"))


def _write_state(state: dict[str, Any]) -> None:
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


@mcp.tool()
def deploy(image: str) -> dict[str, Any]:
    """Pull và deploy một image ci-sandbox lên staging local; không hỗ trợ production."""
    _validate_staging_image(image)
    state = _read_state()
    previous = state.get("current_image")
    env = os.environ.copy()
    env["CI_SANDBOX_IMAGE"] = image
    for args in (("pull",), ("up", "-d")):
        command = ["docker", "compose", "-f", str(COMPOSE_FILE), *args]
        result = subprocess.run(command, cwd=PROJECT_ROOT, env=env,
                                capture_output=True, text=True, timeout=300, check=False)
        output = (result.stdout + "\n" + result.stderr).strip()
        if result.returncode != 0:
            raise RuntimeError(f"Deploy staging failed ({result.returncode}): {output[-2000:]}")
    _write_state({"current_image": image, "previous_image": previous})
    return {"ok": True, "environment": "staging", "image": image, "previous_image": previous}


@mcp.tool()
def health_check() -> dict[str, Any]:
    """Kiểm tra trạng thái container staging qua Docker Compose."""
    return {"ok": True, "environment": "staging", "containers": _run_docker("ps", "--format", "json")}


@mcp.tool()
def rollback() -> dict[str, Any]:
    """Rollback staging về image trước đó; không có thao tác production."""
    previous = _read_state().get("previous_image")
    if not previous:
        raise ValueError("Chưa có image trước đó để rollback staging")
    _validate_staging_image(previous)
    deploy(previous)
    return {"ok": True, "environment": "staging", "rolled_back_to": previous}


if __name__ == "__main__":
    mcp.run()
