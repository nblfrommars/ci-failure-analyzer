package com.nbl.cfa.testgen;

import com.nbl.cfa.github.GitHubApiClient;
import com.nbl.cfa.repo.RepoConfig;
import com.nbl.cfa.repo.RepoRegistry;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/testgen")
public class TestPrController {

    private static final Pattern BRANCH = Pattern.compile("ai/tests-[A-Za-z0-9._-]+");
    private final GitHubApiClient github;
    private final RepoRegistry repoRegistry;
    private final String internalToken;

    public TestPrController(GitHubApiClient github,
                            RepoRegistry repoRegistry,
                            @Value("${cfa.testgen.internal-token:}") String internalToken) {
        this.github = github;
        this.repoRegistry = repoRegistry;
        this.internalToken = internalToken;
    }

    @PostMapping("/open-pr")
    public ResponseEntity<?> openPr(
            @RequestHeader(value = "X-CFA-Internal-Token", required = false) String token,
            @RequestBody OpenPrRequest request) {
        if (internalToken.isBlank() || token == null || !internalToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("unauthorized"));
        }
        RepoConfig repo = request == null ? null : repoRegistry.find(request.repoFullName()).orElse(null);
        if (request == null || repo == null || !repo.enabled() || !repo.features().test()
                || request.installationId() == null || !BRANCH.matcher(request.branch()).matches()
                || request.baseBranch() == null || request.baseBranch().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("repo, installation hoặc branch không hợp lệ"));
        }
        if (!validPath(repo, request.filePath()) || request.content() == null || request.content().isBlank()
                || request.content().length() > 200_000) {
            return ResponseEntity.badRequest().body(new ErrorResponse("file test không hợp lệ"));
        }
        try {
            String url = github.openTestPullRequest(request.installationId(), request.repoFullName(),
                    request.baseBranch(), request.branch(), request.filePath(), request.content(),
                    request.title(), request.body());
            return ResponseEntity.ok(new OpenPrResponse("ok", url));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    private boolean validPath(RepoConfig repo, String path) {
        String prefix = repo.test().appDir() + "/" + repo.test().aiTestsDir() + "/";
        return path != null && path.startsWith(prefix) && path.endsWith(".ts")
                && !path.contains("..") && path.substring(prefix.length()).indexOf('/') < 0;
    }

    public record OpenPrRequest(String repoFullName, Long installationId, String baseBranch,
                                String branch, String filePath, String content, String title, String body) {
    }

    private record OpenPrResponse(String status, String htmlUrl) {
    }

    private record ErrorResponse(String error) {
    }
}
