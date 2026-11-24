package com.nbl.cfa.repo;

import java.nio.file.Path;
import java.util.Map;

public record RepoConfig(
        String repoFullName,
        boolean enabled,
        String llmProfile,
        Features features,
        Deploy deploy,
        Test test) {

    public record Features(boolean triage, boolean deploy, boolean test) {
    }

    public record Deploy(String target, String imageRepository, Path stagingDir,
                         int stagingPort, String stagingUrl) {
    }

    public record Test(Path checkoutPath, String appDir, String aiTestsDir, String baseBranch) {
    }
}
