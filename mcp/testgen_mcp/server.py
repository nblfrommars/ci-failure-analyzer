import os
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP


mcp = FastMCP("testgen-mcp")
PROJECT_ROOT = Path(__file__).resolve().parents[2]
TARGET_ROOT = Path(os.getenv("CI_SANDBOX_ROOT", str(PROJECT_ROOT.parent / "ci-sandbox"))).resolve()
ALLOWED_ROOT = (TARGET_ROOT / "app" / "e2e" / "tests" / "ai-generated").resolve()
MAX_OUTPUT_CHARS = 800
debug_log_path = os.getenv("TESTGEN_DEBUG_LOG")
DEBUG_LOG = Path(debug_log_path) if debug_log_path else None


def _debug_log(message: str) -> None:
    """Ghi log chẩn đoán ngoài stdout để không làm hỏng giao thức MCP stdio."""
    if DEBUG_LOG is None:
        return
    try:
        with DEBUG_LOG.open("a", encoding="utf-8") as handle:
            handle.write(f"{message}\n")
    except OSError:
        pass


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
def run_tests(relative_path: str = "", timeout_seconds: int = 300) -> dict[str, Any]:
    """Chạy Playwright trong app mục tiêu; không nhận shell command tùy ý."""
    _debug_log(f"START relative_path={relative_path!r} timeout_seconds={timeout_seconds} target_root={TARGET_ROOT}")
    if relative_path:
        path = _allowed_test_path(relative_path)
        if not path.exists():
            raise ValueError(f"Không tìm thấy test: {relative_path}")
        test_arg = str(path.relative_to(TARGET_ROOT / "app")).replace("\\", "/")
    else:
        test_arg = "e2e/tests/ai-generated"

    timeout_seconds = max(10, min(timeout_seconds, 600))
    app_root = TARGET_ROOT / "app"
    artifact_dir = Path(tempfile.mkdtemp(prefix="ci-failure-testgen-"))
    node = shutil.which("node") or ("node.exe" if os.name == "nt" else "node")
    playwright_cli = app_root / "node_modules" / "playwright" / "cli.js"
    if not playwright_cli.is_file():
        raise RuntimeError("Không tìm thấy Playwright CLI trong app/node_modules/playwright/cli.js")
    command = [node, str(playwright_cli), "test", test_arg,
               "--reporter=line", "--output", str(artifact_dir)]
    _debug_log(f"SPAWN cwd={app_root} command={command!r} artifact_dir={artifact_dir}")
    try:
        result = subprocess.run(command, cwd=app_root, stdin=subprocess.DEVNULL,
                                capture_output=True, text=True,
                                timeout=timeout_seconds, check=False)
    except subprocess.TimeoutExpired as exc:
        output = ((exc.stdout or "") + "\n" + (exc.stderr or "")).strip()
        _debug_log(f"TIMEOUT test_path={test_arg} output_tail={output[-MAX_OUTPUT_CHARS:]!r}")
        return {
            "ok": False,
            "status": "timeout",
            "exit_code": None,
            "test_path": test_arg,
            "output": output[-MAX_OUTPUT_CHARS:],
            "output_truncated": len(output) > MAX_OUTPUT_CHARS,
        }
    output = (result.stdout + "\n" + result.stderr).strip()
    _debug_log(f"END returncode={result.returncode} test_path={test_arg} output_tail={output[-MAX_OUTPUT_CHARS:]!r}")
    return {
        "ok": result.returncode == 0,
        "status": "passed" if result.returncode == 0 else "failed",
        "exit_code": result.returncode,
        "test_path": test_arg,
        "output": output[-MAX_OUTPUT_CHARS:],
        "output_truncated": len(output) > MAX_OUTPUT_CHARS,
    }


@mcp.tool()
def open_pr(repo_full_name: str, installation_id: int, base_branch: str,
            branch: str, file_path: str, content: str, title: str, body: str) -> dict[str, Any]:
    endpoint = os.getenv("CFA_TESTGEN_OPEN_PR_URL", "http://127.0.0.1:8081/internal/testgen/open-pr")
    internal_token = os.getenv("CFA_TESTGEN_INTERNAL_TOKEN", "")
    payload = {
        "repoFullName": repo_full_name,
        "installationId": installation_id,
        "baseBranch": base_branch,
        "branch": branch,
        "filePath": file_path,
        "content": content,
        "title": title,
        "body": body,
    }
    request = urllib.request.Request(
        endpoint,
        data=__import__("json").dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-CFA-Internal-Token": internal_token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return __import__("json").loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:MAX_OUTPUT_CHARS]
        return {"status": "failed", "http_status": exc.code, "error": detail}
    except (urllib.error.URLError, TimeoutError) as exc:
        return {"status": "failed", "error": str(exc)}
    """Chưa bật: mở PR cần chốt GitHub auth/branch workflow trước; luôn dừng an toàn."""
    return {
        "status": "needs_human",
        "reason": "open_pr chưa được bật; cần chốt GitHub token, branch policy và review guardrail.",
        "branch": branch,
        "title": title,
    }


if __name__ == "__main__":
    mcp.run()
