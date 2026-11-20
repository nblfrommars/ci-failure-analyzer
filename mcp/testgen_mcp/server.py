import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP


mcp = FastMCP("testgen-mcp")
PROJECT_ROOT = Path(__file__).resolve().parents[2]
TARGET_ROOT = Path(os.getenv("CI_SANDBOX_ROOT", str(PROJECT_ROOT.parent / "ci-sandbox"))).resolve()
ALLOWED_ROOT = (TARGET_ROOT / "app" / "e2e" / "tests" / "ai-generated").resolve()
MAX_OUTPUT_CHARS = 800


def _allowed_test_path(relative_path: str) -> Path:
    path = (TARGET_ROOT / "app" / relative_path).resolve()
    if path.parent != ALLOWED_ROOT or path.suffix != ".ts" or path.name in {"", ".", ".."}:
        raise ValueError("Chỉ được dùng file .ts trực tiếp trong app/e2e/tests/ai-generated/")
    return path


@mcp.tool()
def write_test(relative_path: str, content: str) -> dict[str, Any]:
    """Ghi một test TypeScript vào đúng allowlist ai-generated; không ghi file app."""
    path = _allowed_test_path(relative_path)
    if not content.strip():
        raise ValueError("Nội dung test không được rỗng")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")
    return {"ok": True, "path": str(path.relative_to(TARGET_ROOT)), "bytes": len(content.encode("utf-8"))}


@mcp.tool()
def run_tests(relative_path: str | None = None, timeout_seconds: int = 300) -> dict[str, Any]:
    """Chạy Playwright trong app mục tiêu; không nhận shell command tùy ý."""
    if relative_path is not None:
        path = _allowed_test_path(relative_path)
        if not path.exists():
            raise ValueError(f"Không tìm thấy test: {relative_path}")
        test_arg = str(path.relative_to(TARGET_ROOT / "app")).replace("\\", "/")
    else:
        test_arg = "e2e/tests/ai-generated"

    timeout_seconds = max(10, min(timeout_seconds, 600))
    npm = "npm.cmd" if os.name == "nt" else "npm"
    app_root = TARGET_ROOT / "app"
    artifact_dir = Path(tempfile.mkdtemp(prefix="ci-failure-testgen-"))
    command = [npm, "exec", "playwright", "--", "test", test_arg,
               "--reporter=line", "--output", str(artifact_dir)]
    try:
        result = subprocess.run(command, cwd=app_root, capture_output=True,
                                text=True, timeout=timeout_seconds, check=False)
    except subprocess.TimeoutExpired as exc:
        output = ((exc.stdout or "") + "\n" + (exc.stderr or "")).strip()
        return {
            "ok": False,
            "status": "timeout",
            "exit_code": None,
            "test_path": test_arg,
            "output": output[-MAX_OUTPUT_CHARS:],
            "output_truncated": len(output) > MAX_OUTPUT_CHARS,
        }
    output = (result.stdout + "\n" + result.stderr).strip()
    return {
        "ok": result.returncode == 0,
        "status": "passed" if result.returncode == 0 else "failed",
        "exit_code": result.returncode,
        "test_path": test_arg,
        "output": output[-MAX_OUTPUT_CHARS:],
        "output_truncated": len(output) > MAX_OUTPUT_CHARS,
    }


@mcp.tool()
def open_pr(branch: str, title: str, body: str) -> dict[str, Any]:
    """Chưa bật: mở PR cần chốt GitHub auth/branch workflow trước; luôn dừng an toàn."""
    return {
        "status": "needs_human",
        "reason": "open_pr chưa được bật; cần chốt GitHub token, branch policy và review guardrail.",
        "branch": branch,
        "title": title,
    }


if __name__ == "__main__":
    mcp.run()
