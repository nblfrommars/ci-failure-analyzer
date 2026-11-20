import os
import re
from typing import Any

import psycopg
from mcp.server.fastmcp import FastMCP


mcp = FastMCP("readonly-mcp")
REPO_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
DEFAULT_DSN = "postgresql://cfa:cfa_dev_password@localhost:5432/cfa"


def _dsn() -> str:
    return os.getenv("CFA_DB_DSN", DEFAULT_DSN)


def _validate_run(repo_full_name: str, run_id: int, run_attempt: int) -> None:
    if not REPO_PATTERN.fullmatch(repo_full_name):
        raise ValueError("repo_full_name phải có dạng owner/repository")
    if run_id <= 0 or run_attempt <= 0:
        raise ValueError("run_id và run_attempt phải là số dương")


@mcp.tool()
def get_build_status(
    repo_full_name: str,
    run_id: int,
    run_attempt: int = 1,
) -> dict[str, Any]:
    """Đọc trạng thái một workflow run đã được lưu trong PostgreSQL."""
    _validate_run(repo_full_name, run_id, run_attempt)
    query = """
        SELECT p.repo_full_name, r.github_run_id, r.run_attempt,
               r.status, r.conclusion, r.head_sha, r.head_branch,
               r.pull_request_number, r.received_at
          FROM pipeline_run r
          JOIN project p ON p.id = r.project_id
         WHERE p.repo_full_name = %s
           AND r.github_run_id = %s
           AND r.run_attempt = %s
    """
    with psycopg.connect(_dsn(), row_factory=psycopg.rows.dict_row) as conn:
        row = conn.execute(query, (repo_full_name, run_id, run_attempt)).fetchone()
    if row is None:
        return {"found": False, "repo_full_name": repo_full_name, "run_id": run_id}
    row["found"] = True
    for key, value in list(row.items()):
        if hasattr(value, "isoformat"):
            row[key] = value.isoformat()
    return row


@mcp.tool()
def get_logs(
    repo_full_name: str,
    run_id: int,
    run_attempt: int = 1,
    max_chars: int = 8000,
) -> dict[str, Any]:
    """Đọc log lỗi đã làm sạch của workflow run; không gọi API ghi dữ liệu."""
    _validate_run(repo_full_name, run_id, run_attempt)
    max_chars = max(500, min(max_chars, 20000))
    query = """
        SELECT j.github_job_id, j.job_name, j.failed_step,
               j.is_flaky, j.error_type, j.root_cause,
               j.key_evidence, j.cleaned_log
          FROM job_failure j
          JOIN pipeline_run r ON r.id = j.pipeline_run_id
          JOIN project p ON p.id = r.project_id
         WHERE p.repo_full_name = %s
           AND r.github_run_id = %s
           AND r.run_attempt = %s
         ORDER BY j.github_job_id
    """
    with psycopg.connect(_dsn(), row_factory=psycopg.rows.dict_row) as conn:
        rows = conn.execute(query, (repo_full_name, run_id, run_attempt)).fetchall()
    failures = []
    for row in rows:
        log = row.pop("cleaned_log") or ""
        row["log"] = log[-max_chars:]
        row["log_truncated"] = len(log) > max_chars
        failures.append(row)
    return {
        "found": bool(rows),
        "repo_full_name": repo_full_name,
        "run_id": run_id,
        "run_attempt": run_attempt,
        "failures": failures,
    }


if __name__ == "__main__":
    mcp.run()
