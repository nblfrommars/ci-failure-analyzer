package com.nbl.cfa.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class OpenClawAgentClientTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void parsesJsonFromOpenClawCli() throws Exception {
        Path fakeCli = createScript("""
                @echo off
                echo OpenClaw banner
                echo {"ok":true,"status":"completed"}
                """);

        OpenClawAgentClient client =
                new OpenClawAgentClient(fakeCli.toString(), 10, jsonMapper);

        AgentInvocationResult result = client.call(
                "triage",
                new AgentTask(
                        "test-1",
                        "triage",
                        Map.of("repo", "nblfrommars/ci-sandbox")));

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertNotNull(result.payload());
        assertTrue(result.payload().get("ok").asBoolean());
        assertEquals("completed", result.payload().get("status").asText());
    }

    @Test
    void rejectsInvalidAgentId() {
        OpenClawAgentClient client =
                new OpenClawAgentClient("openclaw.cmd", 10, jsonMapper);

        assertThrows(
                IllegalArgumentException.class,
                () -> client.call(
                        "triage;del",
                        new AgentTask("test-2", "triage", Map.of())));
    }

    @Test
    void returnsCliFailure() throws Exception {
        Path fakeCli = createScript("""
                @echo off
                exit /b 7
                """);

        OpenClawAgentClient client =
                new OpenClawAgentClient(fakeCli.toString(), 10, jsonMapper);

        AgentInvocationResult result = client.call(
                "triage",
                new AgentTask("test-3", "triage", Map.of()));

        assertFalse(result.timedOut());
        assertEquals(7, result.exitCode());
    }

    @Test
    void timesOutLongRunningCli() throws Exception {
        Path fakeCli = createScript("""
                @echo off
                ping 127.0.0.1 -n 8 > nul
                echo {"ok":true}
                """);

        OpenClawAgentClient client =
                new OpenClawAgentClient(fakeCli.toString(), 1, jsonMapper);

        AgentInvocationResult result = client.call(
                "triage",
                new AgentTask("test-4", "triage", Map.of()));

        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @TempDir
    Path tempDir;

    private Path createScript(String content) throws Exception {
        Path script = tempDir.resolve("fake-openclaw.cmd");
        Files.writeString(script, content.replace("\n", "\r\n"));
        return script.toAbsolutePath();
    }
}