package com.nbl.cfa.testgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nbl.cfa.github.GitHubApiClient;
import com.nbl.cfa.repo.RepoRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TestPrControllerTest {

    private static final String REPO = "nblfrommars/ci-sandbox";
    private static final String CONTENT = "import { test } from '@playwright/test';";

    @Mock
    private GitHubApiClient github;

    private TestPrController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new TestPrController(github, new RepoRegistry(java.nio.file.Path.of("config/repos.yml")), "local-token");
    }

    @Test
    void rejectsMissingOrWrongInternalToken() {
        ResponseEntity<?> response = controller.openPr(null, request());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(github, never()).openTestPullRequest(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsRepoBranchAndPathOutsideGuardrail() {
        ResponseEntity<?> wrongRepo = controller.openPr("local-token",
                new TestPrController.OpenPrRequest("other/repo", 99L, "main",
                        "ai/tests-counter", "app/e2e/tests/ai-generated/counter.spec.ts", CONTENT, "t", "b"));
        ResponseEntity<?> wrongBranch = controller.openPr("local-token",
                new TestPrController.OpenPrRequest(REPO, 99L, "main",
                        "main", "app/e2e/tests/ai-generated/counter.spec.ts", CONTENT, "t", "b"));
        ResponseEntity<?> wrongPath = controller.openPr("local-token",
                new TestPrController.OpenPrRequest(REPO, 99L, "main",
                        "ai/tests-counter", "app/src/evil.ts", CONTENT, "t", "b"));

        assertEquals(HttpStatus.BAD_REQUEST, wrongRepo.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, wrongBranch.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, wrongPath.getStatusCode());
        verify(github, never()).openTestPullRequest(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private TestPrController.OpenPrRequest request() {
        return new TestPrController.OpenPrRequest(REPO, 99L, "main",
                "ai/tests-counter", "app/e2e/tests/ai-generated/counter.spec.ts", CONTENT, "t", "b");
    }
}
