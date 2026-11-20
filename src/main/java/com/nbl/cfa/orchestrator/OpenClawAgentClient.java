package com.nbl.cfa.orchestrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

        List<String> command = new ArrayList<>(List.of(
                executable,
                "agent",
                "--agent", agentId,
                "--message", message,
                "--json",
                "--timeout", Long.toString(processTimeout.toSeconds())));

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Không khởi động được OpenClaw CLI: " + executable, e);
        }

        try {
            boolean completed = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                String output = readOutput(process);
                return new AgentInvocationResult(agentId, true, -1, null, output);
            }

            String output = readOutput(process);
            return new AgentInvocationResult(
                    agentId,
                    false,
                    process.exitValue(),
                    parseJsonPayload(output),
                    output);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Luồng gọi OpenClaw bị interrupt", e);
        }
    }

    private JsonNode parseJsonPayload(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return jsonMapper.readTree(output.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private String readOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Không đọc được output OpenClaw: " + e.getMessage();
        }
    }

    private void validateAgentId(String agentId) {
        if (agentId == null || !AGENT_ID.matcher(agentId).matches()) {
            throw new IllegalArgumentException("agentId không hợp lệ");
        }
    }
}
