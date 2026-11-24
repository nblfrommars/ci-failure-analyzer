package com.nbl.cfa.orchestrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapter duy nhất để Orchestrator gọi agent OpenClaw theo ID.
 * Không nhận shell command tùy ý và không gọi MCP/LLM trực tiếp.
 */
@Component
public class OpenClawAgentClient {

    private static final Pattern AGENT_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private final String executable;
    private final Duration processTimeout;
    private final JsonMapper jsonMapper;

    public OpenClawAgentClient(
            @Value("${cfa.orchestrator.openclaw.executable:openclaw.cmd}") String executable,
            @Value("${cfa.orchestrator.openclaw.timeout-seconds:300}") long timeoutSeconds,
            JsonMapper jsonMapper) {
        if (timeoutSeconds < 1 || timeoutSeconds > 900) {
            throw new IllegalArgumentException("OpenClaw timeout phải trong khoảng 1..900 giây");
        }
        this.executable = Objects.requireNonNull(executable).isBlank() ? "openclaw.cmd" : executable;
        this.processTimeout = Duration.ofSeconds(timeoutSeconds);
        this.jsonMapper = jsonMapper;
    }

    public AgentInvocationResult call(String agentId, AgentTask task) {
        validateAgentId(agentId);
        Objects.requireNonNull(task, "task không được null");

        final String message;
        try {
            message = "Orchestrator task JSON:\n" + jsonMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalArgumentException("Không serialize được task cho OpenClaw", e);
        }

        List<String> command = List.of(executable, "agent", "--agent", agentId,
                "--message-file", "-", "--json", "--timeout", Long.toString(processTimeout.toSeconds()));
        Path input = null;
        Path stdout = null;
        Path stderr = null;
        Process process = null;
        try {
            input = Files.createTempFile("cfa-agent-task-", ".txt");
            stdout = Files.createTempFile("cfa-agent-stdout-", ".txt");
            stderr = Files.createTempFile("cfa-agent-stderr-", ".txt");
            Files.writeString(input, message, StandardCharsets.UTF_8);
            process = new ProcessBuilder(command)
                    .redirectInput(input.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();

            boolean completed = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                terminate(process);
            }
            String output = Files.readString(stdout, StandardCharsets.UTF_8);
            String diagnostics = Files.readString(stderr, StandardCharsets.UTF_8);
            String raw = diagnostics.isBlank() ? output : output + "\n" + diagnostics;
            return new AgentInvocationResult(agentId, !completed, completed ? process.exitValue() : -1,
                    completed ? parseJsonPayload(output) : null, raw);
        } catch (IOException e) {
            throw new IllegalStateException("Không chạy được OpenClaw CLI: " + executable, e);
        } catch (InterruptedException e) {
            if (process != null) {
                terminate(process);
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Luồng gọi OpenClaw bị interrupt", e);
        } finally {
            deleteTempFile(input);
            deleteTempFile(stdout);
            deleteTempFile(stderr);
        }
    }

    private JsonNode parseJsonPayload(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = jsonMapper.readTree(output.substring(start, end + 1));
            if (!root.has("result")) {
                return root;
            }
            String status = root.path("status").asText();
            if (!"ok".equals(status) && !"completed".equals(status)) {
                return null;
            }
            JsonNode payloads = root.path("result").path("payloads");
            if (!payloads.isArray() || payloads.isEmpty()) {
                return null;
            }
            JsonNode reply = payloads.get(payloads.size() - 1);
            if (reply.path("isError").asBoolean(false) || !reply.path("text").isTextual()) {
                return null;
            }
            String text = reply.path("text").asText().strip();
            if (text.startsWith("```json\n") || text.startsWith("```json\r\n")) {
                if (!text.endsWith("```")) {
                    return null;
                }
                text = text.substring(text.indexOf('\n') + 1, text.length() - 3).strip();
            }
            JsonNode payload = jsonMapper.reader()
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(text);
            return payload.isObject() ? payload : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void deleteTempFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            path.toFile().deleteOnExit();
        }
    }

    private void validateAgentId(String agentId) {
        if (agentId == null || !AGENT_ID.matcher(agentId).matches()) {
            throw new IllegalArgumentException("agentId không hợp lệ");
        }
    }
}
