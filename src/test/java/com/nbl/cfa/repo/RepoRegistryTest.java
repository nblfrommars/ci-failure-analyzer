package com.nbl.cfa.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RepoRegistryTest {

    @Test
    void loadsOnlyConfiguredCiSandboxAndAllowsCloudProfile() {
        RepoConfig config = new RepoRegistry(Path.of("config/repos.yml"))
                .find("nblfrommars/ci-sandbox").orElseThrow();

        assertEquals("cloud", config.llmProfile());
        assertEquals("ghcr.io/nblfrommars/ci-sandbox", config.deploy().imageRepository());
        assertEquals("http://127.0.0.1:5173", config.deploy().stagingUrl());
    }

    @Test
    void rejectsDuplicatePortsAndInvalidPaths() throws Exception {
        Path file = Files.createTempFile("repos-invalid-", ".yml");
        Files.writeString(file, """
                repos:
                  - repo_full_name: owner/one
                    enabled: true
                    llm_profile: local
                    features: {triage: true, deploy: true, test: true}
                    deploy:
                      target: local-compose
                      image_repository: ghcr.io/owner/one
                      staging_dir: deploy/one
                      staging_port: 5173
                      staging_url: http://127.0.0.1:5173
                    test:
                      checkout_path: ../one
                      app_dir: app
                      ai_tests_dir: e2e/tests/ai-generated
                      base_branch: main
                  - repo_full_name: owner/two
                    enabled: true
                    llm_profile: cloud
                    features: {triage: true, deploy: true, test: true}
                    deploy:
                      target: local-compose
                      image_repository: ghcr.io/owner/two
                      staging_dir: deploy/two
                      staging_port: 5173
                      staging_url: http://127.0.0.1:5173
                    test:
                      checkout_path: ../two
                      app_dir: app
                      ai_tests_dir: e2e/tests/ai-generated
                      base_branch: main
                """);

        assertThrows(IllegalStateException.class, () -> new RepoRegistry(file));
        Files.deleteIfExists(file);
    }
}
