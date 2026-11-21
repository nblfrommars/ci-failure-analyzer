# Kế hoạch hoàn thiện CI Failure Analyzer

> Cập nhật: 2026-11-21
> Nguồn yêu cầu: `AGENTS.md` và đối chiếu code hiện tại của repo này.

## 1. Mục tiêu cuối cùng

- [ ] Có demo multi-agent CI/CD chạy được với 3 agent: Deploy, Test, Triage.
- [ ] Có hai profile LLM `cloud`/`local`; tạm phát triển bằng OpenRouter, vẫn giữ Ollama local để chuyển lại khi cần và dùng cho demo/bảo mật.
- [ ] Orchestrator điều phối bằng code/state machine, không để các agent gọi trực tiếp nhau.
- [ ] Có đủ 3 nhánh demo: pass, `APP_BUG`, `STALE_TEST`.
- [ ] Production luôn cần người duyệt; Test Agent chỉ ghi vào vùng `ai-generated/` và mở PR.

## 2. Hiện trạng đã xác nhận

- [x] Đọc `AGENTS.md` và ghi nhận các quyết định D1–D12 cùng các rủi ro U1–U12.
- [x] Repo là Spring Boot, Java 21, Maven; package chính là `com.nbl.cfa`.
- [x] Đã có webhook GitHub tại `/webhooks/github` và xác thực chữ ký.
- [x] Đã có RabbitMQ với queue xử lý `workflow_run`.
- [x] Đã có `WorkflowRunWorker`, GitHub client/auth, log cleaner/preprocessor và lưu dữ liệu pipeline/failure.
- [x] Đã có classifier Ollama và các lớp chạy đánh giá (`EvalRunner`, `DbEvalRunner`).
- [x] Đã có Docker Compose cho PostgreSQL/pgvector và RabbitMQ.
- [x] Ghi nhận yêu cầu mới trong `AGENTS.md`: chế độ phát triển tạm thời là `cloud` qua OpenRouter; đây là trạng thái/định hướng được khai báo, chưa coi là đã kiểm thử trên máy.
- [x] Kiểm thử cloud inference: OpenRouter đã trả lời thành công với model `cohere/north-mini-code:free` qua OpenClaw TUI.
- [ ] Còn thiếu playbook Deploy/Test hoàn chỉnh, webhook/pipeline ghép end-to-end, Telegram và demo 3 nhánh; Orchestrator, staging compose, CI build/push và audit log đã có.
- [x] U2 hoàn tất: gọi agent `main` qua Gateway thành công với provider `openrouter-cloud`, model `cohere/north-mini-code:free`; trả `OK`, thời gian khoảng 9.9 giây, chi phí lượt thử `0`.
- [x] Kiểm tra local inference: `qwen3:4b` đã trả lời được, nhưng không tuân thủ yêu cầu trả lời chính xác và suy diễn nhầm OpenClaw thành OpenCL; cần đánh giá instruction-following/tool calling trước khi dựng agent.
- [x] Kiểm tra bản cài OpenClaw: ban đầu chưa có trong `PATH`, sau đó đã cài và xác nhận `OpenClaw 2026.11.5`.
- [x] Kiểm tra Node: đang là `v24.11.1`, thấp hơn mức OpenClaw yêu cầu `24.16.0+`.

## 3. Việc cần làm theo thứ tự

### P0 — Xác minh nền tảng trước khi viết phần phụ thuộc

- [x] Kiểm tra bản cài và lệnh `openclaw --help`: CLI hoạt động và có lệnh `agents`, `agent`, `gateway`.
- [x] Xác định và cài OpenClaw CLI `2026.11.5` qua npm.
- [ ] Nâng Node lên phiên bản được OpenClaw hỗ trợ (`24.16+`); OpenClaw hiện đã cài nhưng môi trường vẫn cần được chuẩn hóa.
- [x] Xác minh U2 hoàn chỉnh: `openclaw agent --agent main` gọi thành công qua Gateway.
- [x] Xác minh cú pháp quản lý MCP của OpenClaw `2026.11.5`: có `mcp add`, `list`, `probe`, `reload`; các MCP server cần thiết đã được cấu hình và probe.
- [x] Tạo, đăng ký và probe MCP smoke test `smoke-test`; OpenClaw đã nhìn thấy 1 tool (`ping`).
- [x] Tool calling smoke test đạt: agent gọi `smoke-test__ping` và nhận `pong`; `bridgeCalls: 1`. `dispatch_action` xuất hiện như bridge nội bộ của OpenClaw, còn `exec` đã không được dùng.
- [x] Tạo skeleton `readonly-mcp` với `get_build_status` và `get_logs`, đọc PostgreSQL ở chế độ chỉ đọc; đã kiểm tra cú pháp Python.
- [x] Cài dependency, đăng ký và probe `readonly-mcp`; OpenClaw đã nhìn thấy 2 tool.
- [x] Tạo runtime/session mới và kiểm thử `readonly-mcp__get_build_status` thành công với `nblfrommars/ci-sandbox`, run `42751785320`; tool trả đúng trạng thái `failure`, branch, SHA và PR.
- [x] Kiểm thử `readonly-mcp__get_logs` thành công sau khi quota reset; tool trả JSON ngắn cho run `42751785320`.
- [ ] Ghi lại phiên bản OpenClaw, Playwright MCP và Ollama vào tài liệu dự án.
- [x] Chốt D6: Orchestrator mở rộng Spring Boot hiện có; không dùng Spring AI ở lớp Orchestrator vì Orchestrator không gọi LLM. MCP server tiếp tục viết bằng Python.
- [ ] Thử tool calling của `qwen3:4b` với một MCP server tối giản trước khi dựng đủ agent.
- [ ] Đặt cấu hình tài nguyên ban đầu: `num_ctx`, chạy tuần tự, `OLLAMA_MAX_LOADED_MODELS=1`, `OLLAMA_NUM_PARALLEL=1`.

### P1 — Chuẩn bị app mục tiêu và staging

- [x] Chốt `ci-sandbox` là app mục tiêu và staging chạy trên máy local.
- [x] Bổ sung/chốt `Dockerfile` cho app mục tiêu; `ci-sandbox/app/Dockerfile` đã build thành công trên GitHub Actions.
- [x] Tạo `deploy/staging.yml`, health check và cơ chế truyền image tag qua `CI_SANDBOX_IMAGE`; đã pull image SHA `eacecc2de832c71e546f94ee2af0050448590427` và start container staging thành công.
- [ ] Thêm các `data-testid` cần thiết cho luồng demo.
- [ ] Viết workflow CI hoàn chỉnh: `ci-sandbox/.github/workflows/build-image.yml` đã build/push GHCR theo SHA thành công; còn thiếu gọi webhook.
- [x] Cấu hình staging chỉ `docker pull` image từ GHCR, không build lại local.

### P1 — Xây 3 MCP server với quyền tối thiểu

- [x] `readonly-mcp`: đã tạo/probe `get_build_status`, `get_logs`; đang dùng cho Triage Agent.
- [x] `deploy-mcp`: đã đăng ký/probe thành công với 3 tool; agent Deploy đã kiểm thử `health_check`, `deploy` và `rollback` thành công. Rollback từ image `78b631c65228985bda1d21bc96d457e2110fc7b5` về `eacecc2de832c71e546f94ee2af0050448590427`.
- [x] `testgen-mcp`: đã tạo, đăng ký và probe thành công 3 tool; `run_tests` cố định Playwright; `open_pr` đã nối vào endpoint Spring dùng GitHub App installation token, chỉ cho repo demo/branch `ai/tests-*`/file `app/e2e/tests/ai-generated/*.ts`; chưa gọi GitHub API thật.
- [ ] Giới hạn `write_test` vào `e2e/tests/ai-generated/*.ts`; resolve path và từ chối path ngoài allowlist.
- [ ] `run_tests` trả JSON gọn, lỗi tối đa khoảng 800 ký tự.
- [ ] Nối `@playwright/mcp` cho các thao tác trình duyệt.
- [ ] Kiểm thử từng MCP server độc lập bằng MCP Inspector.

### P1 — Dựng agent và playbook

- [x] Cài/cấu hình OpenClaw với provider Ollama local và model khởi đầu `qwen3:4b`; inference local đã chạy được.
- [x] Cấu hình OpenRouter cho profile `cloud` với endpoint custom `openrouter-cloud`, model `cohere/north-mini-code:free` và alias `cloud`; inference thực tế đã thành công.
- [x] Dựng workspace riêng cho Deploy Agent, Test Agent, Triage Agent.
- [x] Cấp đúng MCP server cho từng agent; không cấp `deploy` cho Triage.
- [ ] Không cấp `sessions_send`/`sessions_spawn` nếu vẫn giữ D8.
- [ ] Viết và kiểm thử riêng playbook Deploy.
- [ ] Viết và kiểm thử riêng playbook Test theo luồng PLAN → EXPLORE → GENERATE → VALIDATE → HEAL.
- [x] Viết và kiểm thử riêng playbook Triage cho `APP_BUG` / `STALE_TEST` / `needs_human`.
- [ ] Mỗi agent phải chạy đúng nhiệm vụ qua chat trước khi ghép vào pipeline.

### P1 — Orchestrator và pipeline

- [x] Định nghĩa schema JSON cho task/result và validate mọi đầu vào/đầu ra.
- [x] Triển khai adapter gọi agent OpenClaw sau khi U2 và D6 đã được chốt.
- [ ] Điều phối chuỗi: CI event → deploy → test → triage → rollback/heal/approval.
- [ ] Thực hiện nhánh pass: báo cáo và xin duyệt promote production.
- [ ] Thực hiện nhánh `APP_BUG`: rollback staging, đính kèm bằng chứng, không sửa app.
- [ ] Thực hiện nhánh `STALE_TEST`: heal test trong allowlist và mở PR.
- [ ] Giới hạn tối đa 3 vòng heal và giới hạn token/thời gian mỗi lần chạy.
- [x] Ghi audit log cho run, state transition và event agent/tool trong PostgreSQL.

### P2 — Bảo mật, đánh giá và demo

- [ ] Chạy OpenClaw/Playwright trong container, không dùng root.
- [ ] Kiểm tra guardrail chống prompt injection bằng nội dung trang web giả lập.
- [ ] Chốt quyền GHCR read-only trên staging; rà soát secret và `.env`.
- [ ] Bổ sung 8–10 ca có nhãn; mở rộng dữ liệu cho taxonomy E2E mới.
- [ ] Đo độ chính xác Triage, tỷ lệ test pass lần đầu/sau heal, số vòng heal, thời gian và token.
- [ ] Quay video demo dự phòng ngay khi 3 nhánh chạy được.
- [ ] Hoàn thiện báo cáo theo cấu trúc trong mục 15 của `AGENTS.md`.

## 4. Thứ tự thực hiện đề xuất cho phiên tiếp theo

1. [x] Kiểm tra profile `cloud` OpenRouter hiện tại và inference dữ liệu demo.
2. [x] Xác minh U2 bằng profile đang dùng: gọi thành công agent `main` qua Gateway với `--agent main`.
3. [x] Thử tool calling với MCP smoke test; agent gọi được `smoke-test__ping`.
4. [x] Probe `readonly-mcp` và gọi thành công `readonly-mcp__get_build_status`.
5. [x] Retry `readonly-mcp__get_logs` sau khi quota OpenRouter reset; tool call thành công, trả JSON ngắn.
6. [x] Tạo cơ chế chuyển profile `cloud` ↔ `local` cho classifier/EvalRunner; đã compile Maven thành công.
7. [x] Kiểm tra repo `ci-sandbox` thực tế: đã xác nhận Vite/React, Playwright `@playwright/test` `1.64.1`, workflow `.github/workflows/playwright.yml`, UI counter mẫu, Dockerfile và workflow build/push GHCR.
8. [x] Chốt D6 và chọn cấu trúc thư mục cho MCP/agent/Orchestrator.
9. [x] Dựng staging + CI/GHCR trước khi ghép đủ 3 agent; phần còn thiếu là gọi webhook và ghép pipeline thật.

### Trạng thái sau phiên — 2026-11-19

- Đã hoàn thành U2: gọi agent qua Gateway.
- Đã kiểm thử thành công MCP smoke test.
- Đã probe `readonly-mcp` và đọc được build status từ PostgreSQL.
- Đã retry thành công `readonly-mcp__get_logs` sau khi quota reset; `readonly-mcp` đã kiểm thử đủ 2 tool.
- Đã thêm lớp cấu hình LLM dùng chung cho classifier/EvalRunner: profile `local` dùng Ollama và profile `cloud` dùng endpoint OpenAI-compatible; Maven compile thành công.
- Đã kiểm tra repo `ci-sandbox` local: app Vite/React, Playwright chạy qua `app/playwright.config.ts`, workflow hiện chỉ chạy test.
- Đã sửa lỗi workflow trong repo mục tiêu: `cache-dependency-path` từ `app/package-lock.jsonn` thành `app/package-lock.json`; `git diff --check` không báo lỗi.
- Đã tạo `ci-sandbox/app/Dockerfile`, `app/nginx.conf` và workflow `build-image.yml`; GitHub Actions đã xác nhận job `build-and-push` thành công.
- Chưa thể chạy local Docker build vì Docker daemon hiện bị từ chối truy cập (`npipe:////./pipe/docker_engine`); cần khởi động/đăng nhập Docker Desktop.
- Đã tạo `deploy/staging.yml` và `deploy/staging.env.example`: staging chỉ kéo image GHCR theo `CI_SANDBOX_IMAGE`, không build local, có health check.
- Đã đăng nhập GHCR, pull đúng image SHA và start container `deploy-ci-sandbox-1` thành công bằng `deploy/staging.yml`.
- Đã kiểm tra container staging: `deploy-ci-sandbox-1` ở trạng thái `Up (healthy)`, map `localhost:5173 -> container:80`.
- Đã tạo skeleton `deploy-mcp` và kiểm tra syntax Python thành công; server chỉ cố định `deploy/staging.yml`, chỉ nhận image GHCR SHA của `ci-sandbox`, không có đường production.
- `deploy-mcp` đã được OpenClaw lưu và probe thành công: 3 tool (`deploy`, `health_check`, `rollback`).
- Đã tạo agent `deploy` với workspace riêng `~/.openclaw/workspace-deploy`, cùng model cloud hiện tại.
- Đã áp dụng allowlist riêng cho agent `deploy`; session mới chỉ thấy 3 tool `deploy-mcp__deploy`, `deploy-mcp__health_check`, `deploy-mcp__rollback`.
- Đã kiểm thử `deploy-mcp__health_check` qua agent thành công: staging `ci-sandbox` healthy, port `5173:80`, không dùng `exec`.
- Đã kiểm thử `deploy-mcp__deploy` qua agent Deploy; image được pull/deploy lên staging và health check trả `running (healthy)` tại port `5173`.
- Đã tạo commit thứ hai trong `ci-sandbox`: `1758c19` (`Add stable selector for counter test`), thêm `data-testid="counter-button"` để hỗ trợ Playwright.
- GitHub Actions đã build/push thành công image thứ hai cho commit `Add stable selector for counter test` (run `#2`).
- Đã kiểm thử `deploy` image SHA thứ hai `78b631c65228985bda1d21bc96d457e2110fc7b5`; tool trả staging success.
- Đã kiểm thử `rollback` qua agent Deploy; staging quay đúng về image SHA cũ `eacecc2de832c71e546f94ee2af0050448590427`.
- Đã tạo, syntax check, đăng ký và probe thành công `testgen-mcp` với 3 tool.
- Đã tạo agent `test`, áp dụng allowlist riêng cho `testgen-mcp` và reload MCP runtime thành công; chưa kiểm thử tool qua agent.
- `open_pr` chưa thực hiện ghi/đẩy GitHub, chỉ trả `needs_human` để giữ guardrail review.
- Bước tiếp theo: tạo Test Agent với workspace riêng và allowlist chỉ cho `testgen-mcp`/Playwright, rồi kiểm thử `write_test`/`run_tests` độc lập.
- Đã kiểm thử `testgen-mcp__write_test` qua agent Test; file được tạo đúng tại `app/e2e/tests/ai-generated/counter-smoke.spec.ts`, dùng `getByTestId('counter-button')`.
- `testgen-mcp__run_tests` lần đầu bị timeout; kiểm tra trực tiếp cho thấy Playwright vừa bị vướng artifact cũ vừa chỉ tìm trong `testDir: ./tests`.
- Đã sửa `run_tests` dùng artifact directory temp, reporter `line` và trả timeout có cấu trúc; syntax check thành công.
- Đã sửa `ci-sandbox/app/playwright.config.ts` để nhận `tests/**/*.spec.ts` và `e2e/tests/**/*.spec.ts`; chạy Playwright trực tiếp thành công: `1 passed (5.3s)`.
- Direct Playwright đã pass `1 passed (5.3s)` sau khi sửa config.
- Lần gọi `testgen-mcp__run_tests` qua agent chưa thực thi được vì OpenRouter trả `429 free-models-per-day`; MCP tool chưa nhận được lượt gọi hoàn tất.
- Bước tiếp theo: sau khi quota reset hoặc chuyển tạm agent Test sang profile local, chạy lại `run_tests`; chưa bật thao tác mở PR.

### Trạng thái tạm dừng — 2026-11-20

- Đã hoàn tất `deploy-mcp`: probe, `health_check`, `deploy` và `rollback`; rollback đúng từ image SHA thứ hai về image cũ.
- Đã tạo/probe `testgen-mcp`; `write_test` thành công và tạo `app/e2e/tests/ai-generated/counter-smoke.spec.ts` đúng allowlist.
- Đã sửa Playwright config để nhận cả test viết tay và test AI sinh trong `e2e/tests/**/*.spec.ts`.
- Chạy Playwright trực tiếp thành công: `1 passed (5.3s)`.
- Lần chạy `testgen-mcp__run_tests` qua OpenClaw chưa hoàn tất vì OpenRouter trả `429 free-models-per-day`; không tiếp tục retry trong phiên này.
- Lần retry tiếp theo qua agent Test bị OpenRouter trả `404 This model was not found` cho `cohere/north-mini-code:free`; không phải lỗi MCP/Playwright.
- **Điểm bắt đầu tiếp theo:** đổi model cloud sang model còn khả dụng hoặc chuyển riêng Test Agent sang Ollama local, reload MCP runtime, rồi chạy lại `testgen-mcp__run_tests`.
- Sau khi `run_tests` thành công: quyết định cơ chế `open_pr`, rồi mới dựng Triage Agent và Orchestrator.

## 5. Các điểm không được tự ý thay đổi

- [ ] Không đổi yêu cầu bắt buộc: multi-agent, Playwright test generation, khả năng chạy local và demo.
- [ ] Không mở rộng sang so sánh single-agent/multi-agent hoặc RAG khi chưa có yêu cầu mới.
- [ ] Không cho agent tự sửa code app.
- [ ] Không cho phép deploy production tự động.
- [ ] Không coi U1–U12 là đã đúng khi chưa kiểm tra trên phiên bản cài thật.

## 6. Quy ước cập nhật kế hoạch

- Đánh dấu `[x]` chỉ sau khi đã làm và kiểm thử được.
- Mỗi lần thay đổi guardrail, quyền hoặc luồng production phải ghi rõ trong `AGENTS.md` và báo cho em.
- Sau mỗi milestone, cập nhật phần “Hiện trạng đã xác nhận” và thêm bằng chứng kiểm thử ngắn.

## 7. Hai profile LLM và khả năng chuyển đổi

- [x] Quyết định: tạm phát triển bằng OpenRouter (`cloud`) vì máy yếu, nhưng luôn giữ Ollama (`local`) để chuyển lại; không khóa kiến trúc vào một provider.
- [ ] Tạo profile `local` với Ollama (`ollama/qwen3:4b`).
- [ ] Tạo profile `cloud` với OpenRouter và model cụ thể sau khi thử tool calling/JSON.
- [x] Tạo biến chọn profile `LLM_PROFILE=local|cloud`, cùng các biến `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `LLM_TIMEOUT_SECONDS`; có file mẫu trong `config/llm.*.env.example`.
- [x] Cho classifier và `EvalRunner` đọc cùng profile thay vì gọi riêng endpoint Ollama; compile Maven thành công với cấu hình mới.
- [ ] Xác định cơ chế OpenClaw nhận profile/model và có cần restart Gateway hay không (U1/U10).
- [ ] Thử gọi OpenRouter bằng dữ liệu demo, không dùng code thật, log thật hoặc secret.
- [ ] Kiểm tra giới hạn request, quota, mã lỗi 402/429 và chính sách dữ liệu OpenRouter (U11).
- [ ] Hỏi thầy và ghi quyết định về việc dùng cloud tạm thời cũng như chế độ dùng khi demo/bảo vệ (D10).
- [ ] Sau khi hai profile hoạt động, chạy eval Triage riêng cho `cloud` và `local`; không trộn số liệu.
- [x] Phần classifier/EvalRunner không hardcode provider hoặc model; provider/model được lấy từ `cfa.llm.*` và biến môi trường. Phần OpenClaw vẫn cần xác minh cơ chế nạp profile riêng (U1/U10).
- [ ] Đặt model/provider trong cấu hình OpenClaw theo từng agent hoặc cấu hình mặc định.
- [ ] Dùng biến môi trường hoặc secret store cho API key nếu chuyển sang provider bên ngoài.
- [ ] Giữ nguyên contract JSON giữa Orchestrator và agent để đổi model không làm đổi pipeline.
- [ ] Kiểm thử lại tool calling, structured output, timeout và chất lượng phân loại sau mỗi lần đổi provider.
- [ ] Có thể dùng provider ngoài làm fallback, nhưng chỉ bật sau khi đánh giá tác động bảo mật và dữ liệu rời máy.

### Phương án chuyển đổi dự kiến

Profile `local`:

```text
ollama/qwen3:4b
```

Profile `cloud` tạm thời:

```text
openrouter/<model-id>
```

Khi chuyển đổi, các thành phần vẫn giữ nguyên: MCP server, Playwright, Orchestrator, schema JSON và guardrail. Chỉ thay profile/provider/model, credential và chạy lại bộ kiểm thử tool calling/evaluation.

> Lưu ý bảo mật: profile `cloud` chỉ được dùng với `ci-sandbox` và dữ liệu demo đã lọc. Repo thật, log thật hoặc secret bắt buộc dùng profile `local`; không tự động chuyển `local` sang `cloud`.

## Cập nhật trạng thái — 2026-11-21

- [x] Đã sửa `ci-sandbox/app/playwright.config.ts`: `reuseExistingServer: true` để Playwright dùng lại staging đang chạy khi môi trường có `CI=1`.
- [x] Đã xác minh trực tiếp `testgen-mcp.run_tests` trong môi trường CI: test `counter-smoke.spec.ts` pass khoảng 2–3 giây.
- [x] Đã xác minh Deploy Agent vẫn gọi được `deploy-mcp__health_check` bằng model Particle.
- [x] Đã thử `testgen-mcp__run_tests` qua Test Agent/OpenClaw. Request mới không trả `429`, nhưng bị kẹt ở `dispatch_action` và timeout; chưa đủ bằng chứng để kết luận MCP hỏng.
- [ ] Tạm dừng retry Test Agent cloud cho đến khi quota/rate limit reset; không đổi thêm Playwright hoặc Docker trong thời gian chờ.
- [x] Đã dựng Triage Agent với workspace riêng `~/.openclaw/workspace-triage` và allowlist chỉ gồm `readonly-mcp__get_build_status`, `readonly-mcp__get_logs`.
- [x] Đã smoke test Triage Agent bằng `readonly-mcp__get_build_status`; bridge ghi nhận 1 tool call và trả đúng trạng thái run `42751785320`.
- [x] Đã smoke test `readonly-mcp__get_logs` qua Triage Agent; tool trả đúng lỗi `cache-dependency-path: app/package-lock.jsonn`, không dùng `exec` và không lộ tool ngoài allowlist.
- [x] Đã tạo và kiểm thử `playbooks/triage.md` với quy trình đọc build/log, verdict `APP_BUG` / `STALE_TEST` / `needs_human`, JSON contract và guardrail readonly.
- [x] Đã kiểm thử Triage trên run `42751785320`: hai readonly tool gọi thành công, nhưng phát hiện playbook cần siết contract (lỗi config CI phải là `needs_human`, `evidence` phải là array và không được thêm text ngoài JSON).
- [x] Đã retry kiểm thử phân loại với đầy đủ `repo_full_name`, `run_id`, `run_attempt`: Triage đọc build/log và trả đúng verdict `needs_human`, JSON schema hợp lệ, evidence là array.
- [x] Đã tạo JSON Schema độc lập ngôn ngữ cho Orchestrator tại `orchestrator/schemas/`: task, result, Triage output và examples; đã kiểm tra parse JSON thành công.
- [x] Đã chốt D6: Orchestrator mở rộng Spring Boot, không dùng Spring AI ở lớp điều phối; MCP server tiếp tục viết bằng Python.
- [x] Đã tạo adapter `OpenClawAgentClient` và `AgentTask`/`AgentInvocationResult`: gọi CLI OpenClaw theo agent ID, timeout cấu hình hóa, parse JSON và không nhận shell command tùy ý; Maven compile thành công với JDK 21.
- [x] Đã thêm và chạy `OpenClawAgentClientTest`: 4/4 test pass cho JSON output, agent ID không hợp lệ, CLI failure và timeout.
- [x] Đã dựng state machine Orchestrator thuần Java tại `com.nbl.cfa.orchestrator`, bao phủ nhánh pass, `APP_BUG` rollback, `STALE_TEST` heal và guard invalid transition; unit test state machine pass.
- [x] Đã thêm Flyway migration `V3__orchestrator_audit.sql` và `OrchestratorAuditStore` để lưu orchestrator run, state hiện tại và lịch sử transition/tool-agent event; Maven compile thành công.
- [x] Đã tạo `OrchestratorCoordinator`: map task → agent, gọi `OpenClawAgentClient`, chuyển state và ghi audit event; unit test adapter/state machine vẫn pass sau thay đổi.
- [x] Đã nối trigger `workflow_run` thành công vào Orchestrator qua `WorkflowRunOrchestratorTrigger`, giới hạn repo demo và bật bằng `CFA_ORCHESTRATOR_ENABLED=false` mặc định; chưa bật chạy thật trên webhook.
- [x] Đã thêm `WorkflowRunOrchestratorTriggerTest`: xác minh chỉ run thành công của repo demo với SHA 40 ký tự mới tạo task deploy; repo khác, conclusion khác hoặc SHA sai đều bị bỏ qua.
- [x] Hoàn thiện route hai nhánh E2E trong `OrchestratorCoordinator`: `APP_BUG` gọi Deploy Agent rollback và dừng ở `NEEDS_HUMAN`; `STALE_TEST` gọi Test Agent heal, ép `max_heal_rounds=3` và quay lại `TESTING`; vượt vòng heal bị từ chối.
- [x] Test hai nhánh Orchestrator cùng state machine/webhook/guardrail: 10/10 pass.
- [x] Đã reload MCP và chạy lại Test Agent cloud bằng session mới với model `openrouter-cloud/particle-ai/particle-1:free`; model nhận request và discovery được MCP, nhưng `dispatch_action` bị kẹt khiến tool execution timeout sau khoảng 298 giây, chưa có kết quả test.
- [ ] Điều tra tiếp lỗi `dispatch_action`/bridge khi Test Agent gọi `testgen-mcp__run_tests`; không retry ngay để tránh lặp request và tiêu quota.

### Nhật ký bàn giao cuối ngày — 2026-11-21

- [x] Hoàn tất kiểm thử Spring context với Testcontainers: dùng `pgvector/pgvector:pg16`, timezone JVM UTC; Flyway V1–V3 và RabbitMQ khởi động thành công.
- [x] Chạy nhóm test orchestration: 10/10 test pass, không failure/error.
- [x] Commit và push phần Orchestrator/test lên `origin/main`: `ca2f4de`.
- [x] Commit và push cập nhật kế hoạch: `4b877d5`.
- [x] Reload MCP và thử lại Test Agent cloud bằng session mới; model thực tế là `particle-ai/particle-1:free`.
- [x] Xác nhận model cloud nhận request và discovery được `testgen-mcp`, nhưng `dispatch_action` bị kẹt khi gọi `run_tests`, dẫn tới timeout khoảng 298 giây.
- [ ] Bước tiếp theo: debug `dispatch_action`/MCP bridge, kiểm tra tool call trực tiếp và chỉ retry Test Agent sau khi xác định nguyên nhân.
- [ ] Sau khi Test Agent chạy ổn định: hoàn thiện luồng `open_pr`, bật thử Orchestrator trên webhook demo và cập nhật số liệu đánh giá.
- [ ] Chỉ sau khi Test Agent pass ổn định mới chốt `open_pr`, Triage playbook và Orchestrator.
- [x] Điều tra bước đầu `testgen-mcp__run_tests`: Playwright trực tiếp và hàm `run_tests` chạy pass 1 test trong khoảng 2–5 giây; lỗi không nằm ở test hoặc MCP server.
- [x] Log OpenClaw ghi nhận gateway event loop bị stall khoảng 29 giây, `worker task timed out`; Test Agent cloud treo trong `dispatch_action`. Cần khôi phục/khởi động gateway khỏe mạnh hoặc chạy isolated dev gateway trước khi retry.
- [x] Sau khi gateway khỏe lại, log xác nhận nguyên nhân cloud là OpenRouter HTTP `429` với `particle-ai/particle-1:free`; không phải lỗi `testgen-mcp`/Playwright. Tạm dừng retry cloud và cần đổi model còn quota hoặc chuyển Test Agent sang profile local.
- [x] Đối chứng ngày 2026-11-22: Deploy Agent gọi `deploy-mcp__health_check` thành công và Triage Agent gọi `readonly-mcp__get_build_status` thành công với cùng model cloud; MCP bridge/OpenClaw hoạt động bình thường cho hai agent này.
- [x] Test Agent cô lập ngày 2026-11-22 đã nhận đúng catalog chỉ gồm `testgen-mcp` và đã gọi `testgen-mcp__run_tests`; lần chạy timeout ở `dispatch_action`/tool execution sau 84,7 giây, không phải `tool_unavailable` và không có mã `429` trong run này. Cần debug riêng vòng đời MCP subprocess hoặc tương tác Playwright khi chạy dưới OpenClaw.
- [x] Đã xác định và sửa timeout: Playwright subprocess kế thừa stdin của MCP stdio; đổi `npm.cmd` sang gọi trực tiếp `node node_modules/playwright/cli.js` và đặt `stdin=subprocess.DEVNULL`. Sau reload MCP, Test Agent pass cả chạy thư mục và chạy file cụ thể (`1 passed`, khoảng 1,8–2,5 giây); log chẩn đoán chuyển sang opt-in qua `TESTGEN_DEBUG_LOG`.
- [x] Đã sửa môi trường test context: đặt timezone JVM về UTC và dùng `pgvector/pgvector:pg16` cho Testcontainers; `CiFailureAnalyzerApplicationTests` pass, Flyway áp dụng đủ V1–V3 và Spring context khởi động thành công.
- [x] Đã chạy lại nhóm test orchestration sau thay đổi môi trường: tổng cộng 10 test pass, không failure/error.
- [x] Đã xác minh Test Agent qua OpenClaw sau khi cấu hình MCP env: `testgen-mcp__run_tests` chạy đúng file `counter-smoke.spec.ts`, trả `ok=true`, `status=passed`, `1 passed (5.3s)`.
- [x] Đã triển khai backend mở PR cho Test Agent: tạo branch từ base, ghi một file test AI-generated và mở PR; endpoint yêu cầu token nội bộ, không có merge/production.
- [x] Java compile và Python syntax check pass sau thay đổi `open_pr`; còn cần cấu hình token, restart Spring/MCP và chạy một lần với GitHub demo để xác minh API thật.
- [x] Thêm `TestPrControllerTest`: token thiếu và repo/branch/path ngoài allowlist đều bị từ chối; test guardrail chạy pass 2/2.
- [x] Đã rà soát và commit các thay đổi code/test/plan trong commit `ca2f4de`; các file patch/cấu hình cá nhân vẫn để ngoài commit.
- [x] Đã push commit `ca2f4de` lên remote `origin/main`.
- [x] Đã thêm `OrchestratorPipeline` và mock pipeline không dùng LLM: kiểm thử pass, `APP_BUG`, `STALE_TEST`; phát hiện và sửa lỗi coi `status=failed` là thành công; nhóm pipeline mock 13/13 test pass.
- [x] Đã nối `WorkflowRunOrchestratorTrigger` vào pipeline: webhook success tạo task deploy/test/triage/rollback/heal; nhóm test wiring và mock pipeline pass 13/13.
- [x] Đã viết playbook Deploy và Test với allowlist tool, JSON contract, quy trình validate/heal, giới hạn 3 vòng và guardrail không deploy/merge/sửa app; `git diff --check` pass.

## Checklist bàn giao và kiểm thử quota — 2026-11-22

### Đã làm, đã kiểm thử không cần quota

- [x] Orchestrator pipeline mock cho pass, `APP_BUG`, `STALE_TEST`.
- [x] Webhook success đã tạo task deploy/test/triage/rollback/heal.
- [x] Route rollback/heal, giới hạn 3 vòng và guardrail status agent.
- [x] Playbook Deploy/Test/Triage và JSON contract.
- [x] `open_pr` backend có GitHub App auth, internal token, repo/branch/path allowlist.
- [x] `.env.local.example`, `.gitignore` secret và `dev.local.ps1` cho Windows.
- [x] Unit/wiring/guardrail/mock test: 13/13 pass.

### Cần quota hoặc môi trường thật để kiểm thử

- [ ] Test Agent cloud chạy lại sau 429 và xác minh `open_pr` tạo PR demo thật.
- [ ] Chạy Test Agent đầy đủ PLAN → EXPLORE → GENERATE → VALIDATE → HEAL → OPEN_PR.
- [ ] Chạy Deploy/Test/Triage thật qua webhook demo, không dùng mock.
- [ ] Đo pass lần đầu, heal, token, thời gian và verdict với model cloud/local.

### Còn làm được không cần quota

- [ ] Telegram approval và timeout/reject mặc định.
- [ ] Kết nối `@playwright/mcp` và kiểm thử MCP Inspector.
- [ ] Prompt-injection test, container non-root, GHCR read-only.
- [ ] Bổ sung dữ liệu đánh giá, tài liệu, video demo và báo cáo.
- [ ] Sau khi agent thật ổn định, xóa mock không còn cần thiết và chạy lại test thật.
- [ ] Sau khi quota reset và Test/Deploy/Triage Agent thật chạy ổn định: rà soát, xóa hoặc chuyển các mock pipeline không còn cần thiết, rồi chạy lại bộ test thật và cập nhật số liệu.

## Trạng thái đã xác nhận — 2026-11-22

### Đã hoàn thành

- [x] Deploy Agent gọi được `health_check`, `deploy` và `rollback` qua `deploy-mcp`.
- [x] Triage Agent gọi được `get_build_status` và `get_logs` qua `readonly-mcp`.
- [x] Test Agent gọi được `run_tests` qua OpenClaw; test counter pass `1 passed`.
- [x] `write_test` có allowlist path; `run_tests` trả JSON gọn và có timeout.
- [x] Orchestrator state machine có nhánh pass, `APP_BUG`, `STALE_TEST` và giới hạn heal 3 vòng.
- [x] Route `APP_BUG` gọi rollback; route `STALE_TEST` gọi heal; unit test nhóm này pass 10/10.
- [x] Backend `open_pr` có token nội bộ, repo/branch/path allowlist và không có merge/production.
- [x] Có `dev.local.ps1` và `.env.local.example`; secret local được loại khỏi Git.

### Còn lại, theo thứ tự ưu tiên

- [ ] Xác minh một lần `open_pr` thật sau khi quota model cloud hoạt động; lần thử gần nhất bị 429 hoặc sai trạng thái Spring/port, chưa tạo PR.
- [ ] Ghép pipeline webhook đầy đủ: deploy → test → triage → rollback/heal → approval.
- [ ] Viết/kiểm thử playbook Deploy và Test đầy đủ; nối `@playwright/mcp` cho bước khám phá UI.
- [ ] Thêm Telegram approval, bật thử Orchestrator trên webhook demo và chạy đủ 3 nhánh.
- [ ] Hoàn thiện profile local/cloud, eval 8–10 ca, prompt-injection test, container non-root và báo cáo/demo.
