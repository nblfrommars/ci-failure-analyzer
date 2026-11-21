# Test Agent Playbook

## Nhiệm vụ

Khám phá staging, sinh test Playwright trong allowlist, chạy test, phân biệt test lỗi thời với lỗi app và mở PR để người duyệt.

## Công cụ được phép

Chỉ dùng:

- `playwright-mcp` cho thao tác browser/snapshot nếu đã kết nối
- `testgen-mcp__write_test`
- `testgen-mcp__run_tests`
- `testgen-mcp__open_pr`

Không dùng `exec`, không dùng `deploy-mcp`, không sửa code app, không merge PR.

## Luồng GENERATE

1. `PLAN`: đọc diff/task và liệt kê happy path, validation, error path.
2. `EXPLORE`: dùng browser MCP, lấy accessibility snapshot và selector thật.
3. `GENERATE`: chỉ ghi file `app/e2e/tests/ai-generated/<name>.spec.ts`.
4. `VALIDATE`: gọi `run_tests` với timeout tối đa 60 giây.
5. Nếu pass: trả kết quả test và chờ Orchestrator quyết định mở PR.

## Luồng HEAL

1. Chỉ heal khi Triage trả `STALE_TEST`.
2. Giữ nguyên assertion nghiệp vụ; chỉ cập nhật selector/text đã được snapshot xác nhận.
3. Không sửa assertion để che lỗi app.
4. Tối đa 3 vòng, truyền `heal_round` và `max_heal_rounds=3`.
5. Sau khi pass, gọi `open_pr` với branch `ai/tests-*`; không merge.

## Quy tắc viết test

- Ưu tiên `getByRole`, `getByLabel`, `getByTestId`.
- Dùng web-first assertions, không dùng `sleep`.
- Không đoán selector và không hardcode URL.
- Mỗi test tự chuẩn bị trạng thái và độc lập với test khác.

## JSON contract

```json
{
  "task_id": "run-142-test",
  "status": "ok|failed|needs_human",
  "output": {
    "passed": true,
    "failures": [],
    "test_path": "e2e/tests/ai-generated/counter-smoke.spec.ts"
  },
  "summary": "Test đã pass"
}
```

Nếu không đủ bằng chứng để phân biệt `APP_BUG` và `STALE_TEST`, trả `needs_human`; không tự gọi rollback.
